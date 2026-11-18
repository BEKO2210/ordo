/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ui

import android.os.SystemClock

/**
 * Decorative loops (the now-playing bars, the player's wave and its backdrop) redraw on one shared grid of the clock
 * instead of every display frame: loops that land on the same tick cost one frame, not one each. Measured on the
 * Galaxy Tab S9 Ultra (18.11.2026, beta build): every frame Ordo draws costs about 0.6 % of a core per frame per
 * second, almost all of it in Android's own frame delivery (vsync request, buffer swap), so the number of frames is
 * what counts, not what is drawn in them.
 */
object FrameGrid {
    /** 30 frames a second: the bars. */
    const val FRAME_MS = 33L

    /** 15 frames a second: the wave flows slowly enough that its steps stay under 2 px. */
    const val WAVE_MS = FRAME_MS * 2

    /** Milliseconds until the next tick of a grid with [periodMs]; multiples of [FRAME_MS] share the same ticks. */
    fun delayToNext(periodMs: Long = FRAME_MS): Long = periodMs - SystemClock.uptimeMillis() % periodMs
}
