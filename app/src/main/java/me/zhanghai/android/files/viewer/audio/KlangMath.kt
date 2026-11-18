/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.graphics.Path
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import me.zhanghai.android.files.player.AudioSettings
import me.zhanghai.android.files.player.ReverbPreset

/** Pure helpers of the Sound sheet, kept apart from the views so they can be unit tested. */
internal object KlangMath {
    val SPEED_OPTIONS = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)

    /** Gains move in 0.5 dB steps. */
    fun quantizeGain(db: Float): Float =
        ((db * 2).roundToInt() / 2f).coerceIn(AudioSettings.MIN_GAIN_DB, AudioSettings.MAX_GAIN_DB)

    /** [fraction] 0 is the top of the plot (maximum gain), 1 the bottom (minimum gain). */
    fun gainAtFraction(fraction: Float): Float = quantizeGain(
        AudioSettings.MAX_GAIN_DB -
            fraction.coerceIn(0f, 1f) * (AudioSettings.MAX_GAIN_DB - AudioSettings.MIN_GAIN_DB)
    )

    fun isFlat(gains: List<Float>): Boolean = gains.all { it == 0f }

    /** Strength is stored as 0..1000. */
    fun strengthFraction(strength: Int): Float = (strength / 1_000f).coerceIn(0f, 1f)

    fun strengthPercent(strength: Int): Int = (strengthFraction(strength) * 100).roundToInt()

    /** Crossfeed, warmth and clarity steps 1..3 as a third, two thirds, full. */
    fun levelFraction(level: Int): Float = level.coerceIn(1, AudioSettings.LEVELS) / AudioSettings.LEVELS.toFloat()

    /** Stereo width 0..150 % as 0..1. */
    fun widthFraction(percent: Int): Float =
        percent.coerceIn(0, AudioSettings.MAX_WIDTH_PERCENT) / AudioSettings.MAX_WIDTH_PERCENT.toFloat()

    /** Room size of a reverb preset for its picture: the smallest room a quarter, the concert hall full. */
    fun roomFraction(preset: ReverbPreset): Float = (preset.ordinal + 1) / ReverbPreset.entries.size.toFloat()

    /** Share of the sleep timer still to run; "end of track" has no known length and shows a full ring. */
    fun sleepFraction(endOfTrack: Boolean, minutes: Int, remainingMs: Long): Float = when {
        endOfTrack -> 1f
        minutes <= 0 -> 0f
        else -> (remainingMs / (minutes * 60_000f)).coerceIn(0f, 1f)
    }

    /** Columns of the lower cards: two when each gets at least [minimumDp] beside a [gapDp] gap, else one. */
    fun columns(contentDp: Float, gapDp: Float, minimumDp: Float): Int = if ((contentDp - gapDp) / 2 >= minimumDp) 2 else 1

    /** Index of the speed button that equals [speed], or -1 when the speed is in between. */
    fun speedOptionIndex(speed: Float): Int = SPEED_OPTIONS.indexOfFirst { abs(it - speed) < 0.005f }

    /** 0 = fully left, 0.5 = centre, 1 = fully right. */
    fun balanceFraction(balance: Int): Float = ((balance.coerceIn(-100, 100) + 100) / 200f)

    /** A running timer never shows "0 min". */
    fun remainingMinutes(remainingMs: Long): Int = ceil(remainingMs.coerceAtLeast(0L) / 60_000.0).toInt().coerceAtLeast(1)

    /**
     * Smooth Catmull-Rom spline through the points as cubic Béziers, appended to [path] (which is reset first).
     * Allocation-free, so it can run while drawing.
     */
    fun spline(path: Path, xs: FloatArray, ys: FloatArray, count: Int) {
        path.reset()
        if (count == 0) return
        path.moveTo(xs[0], ys[0])
        for (i in 0 until count - 1) {
            val p0 = maxOf(i - 1, 0)
            val p3 = minOf(i + 2, count - 1)
            path.cubicTo(
                xs[i] + (xs[i + 1] - xs[p0]) / 6f, ys[i] + (ys[i + 1] - ys[p0]) / 6f,
                xs[i + 1] - (xs[p3] - xs[i]) / 6f, ys[i + 1] - (ys[p3] - ys[i]) / 6f,
                xs[i + 1], ys[i + 1]
            )
        }
    }
}
