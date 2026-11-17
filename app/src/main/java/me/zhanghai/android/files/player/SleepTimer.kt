/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

object SleepTimerSchedule {
    const val FADE_DURATION_MS = 10_000L

    fun deadline(nowElapsedMs: Long, minutes: Int): Long =
        nowElapsedMs + minutes.coerceAtLeast(0) * 60_000L

    fun volume(deadlineElapsedMs: Long, nowElapsedMs: Long): Float =
        ((deadlineElapsedMs - nowElapsedMs).toFloat() / FADE_DURATION_MS).coerceIn(0f, 1f)

    fun endOfTrackVolume(durationMs: Long, positionMs: Long): Float {
        if (durationMs <= 0L) return 1f
        return ((durationMs - positionMs).toFloat() / FADE_DURATION_MS).coerceIn(0f, 1f)
    }
}
