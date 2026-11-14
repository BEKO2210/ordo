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
    private const val IDLE_MILLIS = 3 * 60_000L

    @Volatile
    private var engine: LlamaEngine? = null
    private var unloadJob: Job? = null

    val isLoaded: Boolean
        get() = engine != null

    /** Loads the model and its fixed prompt part; fast when it is already loaded. */
    suspend fun warmUp(context: Context) {
        withContext(dispatcher) { ensure(context) }
    }

    /** Null means the model answered with something that is not a plan. */
    suspend fun plan(context: Context, text: String): CommandPlan? = withContext(dispatcher) {
        val answer = ensure(context).generate(CommandPlanner.suffix(text), CommandPlanner.GRAMMAR, 200)
            ?: throw IOException("generation cancelled or failed")
        CommandPlan.parse(answer)
    }

    /** Safe from any thread: stops a running generation at the next token. */
    fun cancel() {
        engine?.cancel()
    }

    fun release() {
        scope.launch { close() }
    }

    private fun ensure(context: Context): LlamaEngine {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(IDLE_MILLIS)
            close()
        }
        engine?.let { return it }
        val file = CommandModel.file(context.applicationContext)
        val loaded = LlamaEngine.load(file.path, threads(), 2048)
            ?: throw IOException("model load failed")
        if (!loaded.prepare(CommandPlanner.PREFIX)) {
            loaded.close()
            throw IOException("prompt prefix failed")
        }
        engine = loaded
        return loaded
    }

    private fun close() {
        engine?.close()
        engine = null
    }

    // The big cores only: more threads fight the little cores and make the phone stutter.
    private fun threads(): Int = Runtime.getRuntime().availableProcessors().let { if (it >= 8) 4 else (it / 2).coerceAtLeast(1) }
}
