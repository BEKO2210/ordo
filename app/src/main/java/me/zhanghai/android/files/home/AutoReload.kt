/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs [load] for the home screen. An explicit reload (pull, menu, coming back) replaces the one running, so an older
 * result never overwrites a newer one. Changes on disk only [signal]: a burst becomes one load ([ReloadDebounce]),
 * and a load already running is never cancelled for them – it finishes and runs once more, so during a long copy
 * the numbers still arrive.
 */
class AutoReload(
    private val scope: CoroutineScope,
    quietMs: Long,
    maxWaitMs: Long,
    private val now: () -> Long,
    private val load: suspend () -> Unit
) {
    private val debounce = ReloadDebounce(quietMs, maxWaitMs)
    private var pending: Job? = null
    private var running: Job? = null
    private var dirty = false

    fun reloadNow() {
        pending?.cancel()
        debounce.reset()
        dirty = false
        running?.cancel()
        start()
    }

    fun signal() {
        pending?.cancel()
        val wait = debounce.delayFor(now())
        pending = scope.launch {
            delay(wait)
            debounce.reset()
            if (running?.isActive == true) dirty = true else start()
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
            if (dirty) {
                dirty = false
                start()
            }
        }
    }
}
