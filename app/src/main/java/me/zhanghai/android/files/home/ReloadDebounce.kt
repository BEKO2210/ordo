/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

/**
 * Folds a burst of "something changed" signals into one reload: it waits until [quietMs] pass without a new signal,
 * but never longer than [maxWaitMs] after the first one, so a long copy still shows progress now and then.
 */
class ReloadDebounce(private val quietMs: Long, private val maxWaitMs: Long) {
    private var firstAt = -1L

    /** A signal at [now] (ms); returns how long to wait before reloading. */
    fun delayFor(now: Long): Long {
        if (firstAt < 0) firstAt = now
        return minOf(quietMs, firstAt + maxWaitMs - now).coerceAtLeast(0)
    }

    /** The reload ran (or was dropped): the next signal starts a new burst. */
    fun reset() {
        firstAt = -1L
    }
}
