/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ui

import android.os.SystemClock

/**
 * Decorative loops (the player's wave and its backdrop) redraw on one shared grid of the clock instead of every
 * display frame: two loops that land on the same tick cost one frame, and 30 frames a second for slow motion keep the
 * battery out of it. Measured on the Galaxy Tab S9 Ultra: every-vsync loops cost about 25 % of a core (see
 * docs/AUDIO-PLAYER.md).
 */
object FrameGrid {
    const val FRAME_MS = 33L

    /** Milliseconds until the next tick of a grid with [periodMs] (a multiple of [FRAME_MS] stays on the same ticks). */
    fun delayToNext(periodMs: Long = FRAME_MS): Long = periodMs - SystemClock.uptimeMillis() % periodMs
}
