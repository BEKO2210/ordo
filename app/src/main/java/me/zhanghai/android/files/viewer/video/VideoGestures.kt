/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

import kotlin.math.abs

/**
 * Which swipe a finger on the picture makes, without Android in it (unit-tested). A swipe only starts after twice the
 * touch slop, and only along a clear axis: |dy| > 2·|dx| is vertical (left half brightness, right half volume),
 * |dx| > 2·|dy| horizontal (seek). Anything in between stays undecided, so a sloppy tap or diagonal does nothing.
 * A touch that started in the system's gesture areas never becomes one ([down] with `allowed` false).
 */
internal class VideoGestures(private val slopPx: Float) {
    enum class Mode { NONE, PENDING, BRIGHTNESS, VOLUME, SEEK }

    var mode = Mode.NONE
        private set
    private var startX = 0f
    private var startY = 0f

    fun down(x: Float, y: Float, allowed: Boolean) {
        startX = x
        startY = y
        mode = if (allowed) Mode.PENDING else Mode.NONE
    }

    /** The mode after this move; once decided it stays until the finger lifts. */
    fun move(x: Float, y: Float, width: Int): Mode {
        if (mode != Mode.PENDING) return mode
        val dx = abs(x - startX)
        val dy = abs(y - startY)
        mode = when {
            dy > 2 * slopPx && dy > 2 * dx -> if (startX < width / 2f) Mode.BRIGHTNESS else Mode.VOLUME
            dx > 2 * slopPx && dx > 2 * dy -> Mode.SEEK
            else -> Mode.PENDING
        }
        return mode
    }

    fun dx(x: Float): Float = x - startX

    fun dy(y: Float): Float = y - startY

    fun cancel() {
        mode = Mode.NONE
    }

    companion object {
        // mpv-android's CONTROL_SEEK_MAX: a sweep over the whole width is 2:30.
        const val SEEK_FULL_WIDTH_MS = 150_000L
        // A sweep over 60 % of the height goes from 0 to 100 %.
        const val LEVEL_FULL_HEIGHT_FRACTION = 0.6f

        /** Where a horizontal swipe of [dxPx] lands, within the video; unknown length stays unbounded above. */
        fun seekTarget(startMs: Long, dxPx: Float, widthPx: Int, durationMs: Long): Long {
            if (widthPx <= 0) return startMs
            val target = startMs + (dxPx / widthPx * SEEK_FULL_WIDTH_MS).toLong()
            return if (durationMs > 0) target.coerceIn(0L, durationMs) else target.coerceAtLeast(0L)
        }

        /** Back near where it started: the swipe is cancelled and nothing jumps. */
        fun isCancelled(dxPx: Float, slopPx: Float): Boolean = abs(dxPx) < 2 * slopPx

        /** Brightness or volume (0..1) after a vertical swipe of [dyPx] (up is negative, so up raises it). */
        fun levelAfter(start: Float, dyPx: Float, heightPx: Int): Float {
            if (heightPx <= 0) return start
            return (start - dyPx / (heightPx * LEVEL_FULL_HEIGHT_FRACTION)).coerceIn(0f, 1f)
        }
    }
}
