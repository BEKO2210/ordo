/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs [load] for the home screen. An explicit reload (pull, menu, coming back) replaces the one running, so an older
 * result never overwrites a newer one. Changes on disk only [signal]: a burst becomes one load ([ReloadDebounce]),
 * and a load already running is never cancelled for them – it finishes and runs once more, so during a long copy
 * the numbers still arrive. An automatic load waits until [minPauseMs] after the end of the last load, so a long copy
 * does not keep the disk busy with loads back to back; explicit reloads never wait.
 */
class AutoReload(
    private val scope: CoroutineScope,
    quietMs: Long,
    maxWaitMs: Long,
    private val minPauseMs: Long,
    private val now: () -> Long,
    private val load: suspend () -> Unit
) {
    private val debounce = ReloadDebounce(quietMs, maxWaitMs)
    private var pending: Job? = null
    private var running: Job? = null
    private var dirty = false
    private var nextAuto = 0L
    private var pausing: Job? = null

    fun reloadNow() {
        pending?.cancel()
        debounce.reset()
        dirty = false
        running?.cancel()
        start()
    }

    fun signal() {
        // A load already waiting out its pause will see this change too; restarting the quiet time would only delay it.
        if (pausing?.isActive == true) return
        pending?.cancel()
        val wait = debounce.delayFor(now())
        pending = scope.launch {
            delay(wait)
            debounce.reset()
            if (running?.isActive == true) dirty = true else startAfterPause()
        }
    }

    /** Signals stop counting (the screen is hidden); a load already running finishes. */
    fun stop() {
        pending?.cancel()
        debounce.reset()
        dirty = false
    }

    private fun start() {
        running = scope.launch {
            load()
            nextAuto = now() + minPauseMs
            if (dirty) {
                dirty = false
                // In pending, so stop() drops it like any other automatic load still waiting; it covers a change
                // still in its quiet time, which would otherwise start a second load beside it.
                pending?.cancel()
                pending = scope.launch { startAfterPause() }
            }
        }
    }

    private suspend fun startAfterPause() {
        pausing = currentCoroutineContext()[Job]
        delay(nextAuto - now())
        start()
    }
}
