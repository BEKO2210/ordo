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
import java.io.File
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
        if (!loaded.prepare(CommandPlanner.prefix(design.system))) {
            loaded.close()
            throw IOException("prompt prefix failed")
        }
        engine = loaded
        scheduleUnload()
        return loaded
    }

    private fun close() {
        engine?.close()
        engine = null
    }

    private const val CONTEXT_SIZE = 2048

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
