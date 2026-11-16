/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.llm.LlamaEngine
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import android.os.SystemClock
import android.util.Log
import me.zhanghai.android.files.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Keeps the model loaded while the command sheet is in use and frees its ~3 GB after a quiet spell.
 * Every native call runs on one thread, as [LlamaEngine] requires.
 */
object CommandEngine {
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "ordo-llm") }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    @Volatile
    private var engine: LlamaEngine? = null
    private var loadedThreads = 0
    private var loadedSystem = ""
    private var unloadJob: Job? = null

    val isLoaded: Boolean
        get() = engine != null

    /** Loads the model and its fixed prompt part; fast when it is already loaded. */
    suspend fun warmUp(context: Context, design: CommandDesign) {
        withContext(dispatcher) { ensure(context, design) }
    }

    /** Null means the model answered with something that is not a plan. */
    suspend fun plan(context: Context, text: String, design: CommandDesign): CommandPlan? = withContext(dispatcher) {
        val answer = ensure(context, design).generate(CommandPlanner.suffix(text), design.grammar, 200)
        scheduleUnload()
        CommandPlan.parse(answer ?: throw IOException("generation cancelled or failed"))
    }

    /** Safe from any thread: stops a running generation at the next token. */
    fun cancel() {
        engine?.cancel()
    }

    /** Frees the model; returns once any load or answer in progress has finished and memory is free. */
    suspend fun release() {
        withContext(dispatcher) { close() }
    }

    private fun scheduleUnload() {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(idleMinutes(Settings.AI_SPEED.valueCompat) * 60_000L)
            close()
        }
    }

    private fun ensure(context: Context, design: CommandDesign): LlamaEngine {
        unloadJob?.cancel()
        val threads = threadsFor(Settings.AI_SPEED.valueCompat, fastCores(), Runtime.getRuntime().availableProcessors())
        engine?.let {
            if (loadedThreads == threads && loadedSystem == design.system) return it.also { scheduleUnload() }
            // The speed setting or the prompt changed: load again.
            close()
        }
        val file = CommandModel.file(context.applicationContext)
        val loaded = LlamaEngine.load(file.path, threads, CONTEXT_SIZE)
            ?: throw IOException("model load failed")
        loadedThreads = threads
        loadedSystem = design.system
        // A saved prompt state skips reading the prompt again (the slow part of a cold start). It is keyed by
        // model, prompt, grammar, context size and app version, so a change in any of them never loads it.
        val state = stateFile(context.applicationContext, design)
        val started = SystemClock.elapsedRealtime()
        if (state.isFile && loaded.restoreState(state.path)) {
            Log.i(TAG, "prompt state restored: ${state.length()} bytes in ${SystemClock.elapsedRealtime() - started} ms")
        } else {
            state.delete()
            if (!loaded.prepare(CommandPlanner.prefix(design.system))) {
                loaded.close()
                throw IOException("prompt prefix failed")
            }
            Log.i(TAG, "prompt read in ${SystemClock.elapsedRealtime() - started} ms")
            saveState(loaded, state)
        }
        engine = loaded
        scheduleUnload()
        return loaded
    }

    /** Private, never backed up (noBackupFilesDir); deleted together with the model. */
    fun stateDir(context: Context): File = File(context.noBackupFilesDir, "llm-state")

    private fun stateFile(context: Context, design: CommandDesign): File {
        val key = listOf(CommandModel.SHA256, CommandPlanner.prefix(design.system), design.grammar, CONTEXT_SIZE,
            BuildConfig.VERSION_CODE).joinToString("\u0000")
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(stateDir(context), "prompt-${hash.take(32)}.state")
    }

    /** Saves the state when there is room for it and 1 GB to spare; otherwise skips it (the next start reads the prompt). */
    private fun saveState(engine: LlamaEngine, file: File) {
        val dir = file.parentFile!!
        // Only one state is ever kept: an older one is for another prompt, model or version.
        dir.listFiles()?.forEach { if (it != file) it.delete() }
        if (!dir.isDirectory && !dir.mkdirs()) return
        val size = engine.stateSize
        if (size <= 0 || dir.usableSpace < size + MIN_FREE_AFTER_STATE) {
            Log.i(TAG, "prompt state not saved: $size bytes, ${dir.usableSpace} free")
            return
        }
        val started = SystemClock.elapsedRealtime()
        if (engine.saveState(file.path)) {
            Log.i(TAG, "prompt state saved: ${file.length()} bytes in ${SystemClock.elapsedRealtime() - started} ms")
        } else {
            file.delete()
        }
    }

    private fun close() {
        engine?.close()
        engine = null
    }

    private const val TAG = "CommandEngine"
    private const val CONTEXT_SIZE = 2048
    private const val MIN_FREE_AFTER_STATE = 1_000_000_000L

    /** How long the model stays loaded after the last answer: turbo saves the next cold start, gentle frees memory sooner. */
    internal fun idleMinutes(speed: String): Long = when (speed) {
        "turbo" -> 10
        "eco" -> 1
        else -> 3
    }

    /**
     * Fast cores only: threads on the little cores hold the fast ones back and make the phone stutter. Turbo takes
     * every fast core up to 6, balanced up to 4, gentle 2. [fastCores] is 0 when unknown.
     */
    internal fun threadsFor(speed: String, fastCores: Int, cores: Int): Int {
        val fast = if (fastCores in 1 until cores) fastCores else if (cores >= 8) 4 else (cores / 2).coerceAtLeast(1)
        return when (speed) {
            "turbo" -> fast.coerceAtMost(6)
            "eco" -> 2.coerceAtMost(fast)
            else -> fast.coerceAtMost(4)
        }
    }

    /** Cores clocked above the slowest cluster; 0 when the clocks cannot be read or all are equal. */
    private fun fastCores(): Int {
        val clocks = (0 until Runtime.getRuntime().availableProcessors()).map {
            runCatching { File("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }
                .getOrNull() ?: return 0
        }
        val slowest = clocks.minOrNull() ?: return 0
        return clocks.count { it > slowest }
    }
}
