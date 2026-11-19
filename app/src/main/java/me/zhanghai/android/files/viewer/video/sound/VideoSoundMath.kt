/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.sound

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure audio maths for the video sound chain (no Android / media APIs). */
internal object VideoSoundMath {
    const val DIALOG_OFF = 0
    const val DIALOG_LIGHT = 1
    const val DIALOG_STRONG = 2

    /** Linear centre-channel gain in a multichannel-to-stereo downmix. */
    fun centreGain(dialogLevel: Int): Float = when (dialogLevel.coerceIn(0, 2)) {
        DIALOG_OFF -> 0.7071f // -3 dB, ITU-R BS.775
        DIALOG_LIGHT -> 1.0f
        else -> 1.4125f // +3 dB
    }

    /**
     * Dialog-over-rest loudness distance the enhancer aims at, in LU: Light 10 LU (preferred speech-over-music distance),
     * Strong 15 LU (speech over ambience, also the hearing-help level). Torcoli, Freke-Morin, Paulus, Simon, Shirley,
     * "Preferred Levels for Background Ducking to Produce Esthetically Pleasing Audio for TV with Clear Speech",
     * JAES 67(12), 2019, doi:10.17743/jaes.2019.0052. Off: null.
     */
    fun dialogTargetLu(level: Int): Float? = when (level.coerceIn(0, 2)) {
        DIALOG_OFF -> null
        DIALOG_LIGHT -> 10f
        else -> 15f
    }

    /** The most the enhancer moves dialog and rest apart, in dB (half up for dialog, half down for the rest). */
    const val DIALOG_CAP_DB = 18f

    /** Speech band of the stereo heuristic (mid signal): the -3 dB edges of [speechBand]. */
    const val SPEECH_LOW_HZ = 300f
    const val SPEECH_HIGH_HZ = 4000f

    /**
     * How far dialog and rest must move apart: the missing distance to [targetLu], never negative, at most
     * [DIALOG_CAP_DB]; 0 while no dialog is measured ([distanceLu] NaN).
     */
    fun dialogBoostDb(distanceLu: Float, targetLu: Float): Float =
        if (distanceLu.isNaN()) 0f else (targetLu - distanceLu).coerceIn(0f, DIALOG_CAP_DB)

    /** Loudness distance in LU of two K-weighted mean squares; NaN when the dialog part is silent (below -60 dB). */
    fun distanceLu(dialogMeanSquare: Double, restMeanSquare: Double): Float = when {
        dialogMeanSquare < SILENT_MEAN_SQUARE -> Float.NaN
        restMeanSquare < SILENT_MEAN_SQUARE -> DIALOG_CAP_DB + 30f
        else -> (10 * log10(dialogMeanSquare / restMeanSquare)).toFloat()
    }

    private const val SILENT_MEAN_SQUARE = 1e-6

    class CompressorParams(
        val thresholdDb: Float,
        val ratio: Float,
        val kneeDb: Float,
        val attackMs: Float,
        val releaseMs: Float,
        val makeUpDb: Float
    )

    /** Night-mode linked compressor. */
    val NIGHT = CompressorParams(-30f, 4f, 6f, 5f, 250f, 9f)

    /**
     * Soft-knee feed-forward gain computer; returns gain change in dB (<= 0).
     * Giannoulis, Massberg, Reiss, JAES 60(6) 2012, eq. 4.
     */
    fun gainReductionDb(levelDb: Float, thresholdDb: Float, ratio: Float, kneeDb: Float): Float {
        if (ratio <= 1f) return 0f
        val x = levelDb
        val t = thresholdDb
        val w = kneeDb
        if (w <= 0f) {
            return if (x <= t) 0f else (t + (x - t) / ratio) - x
        }
        val half = w / 2f
        return when {
            x < t - half -> 0f
            x > t + half -> (t + (x - t) / ratio) - x
            else -> (1f / ratio - 1f) * (x - t + half) * (x - t + half) / (2f * w)
        }
    }

    /** One-pole smoothing coefficient; [timeMs] <= 0 → 0. */
    fun coefficient(timeMs: Float, sampleRate: Int): Float {
        if (timeMs <= 0f) return 0f
        return exp(-1.0 / (sampleRate * timeMs / 1000.0)).toFloat()
    }

    /** A biquad, normalised so a0 = 1. */
    class Biquad(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float) {
        constructor(c: DoubleArray) : this(c[0].toFloat(), c[1].toFloat(), c[2].toFloat(), c[3].toFloat(), c[4].toFloat())
    }

    /**
     * RBJ Audio EQ Cookbook band-pass (0 dB peak) over [SPEECH_LOW_HZ]..[SPEECH_HIGH_HZ]: centre at their geometric
     * mean, Q = centre / bandwidth. Its complement (1 − H) is the cookbook notch with the same poles, and the two are
     * power-complementary: the mid splits into "dialog" and "rest" that add up to it exactly.
     */
    fun speechBand(sampleRate: Int): Biquad {
        val centre = kotlin.math.sqrt((SPEECH_LOW_HZ * SPEECH_HIGH_HZ).toDouble())
        val q = centre / (SPEECH_HIGH_HZ - SPEECH_LOW_HZ)
        val w0 = 2.0 * PI * centre / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        return Biquad(doubleArrayOf(alpha / a0, 0.0, -alpha / a0, -2 * cos(w0) / a0, (1 - alpha) / a0))
    }

    /** |H(e^jw)| of a biquad in dB (for tests). */
    fun magnitudeDb(p: Biquad, freqHz: Float, sampleRate: Int): Float {
        val w = 2.0 * PI * freqHz / sampleRate
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2.0 * w)
        val s2 = sin(2.0 * w)
        val nr = p.b0 + p.b1 * c1 + p.b2 * c2
        val ni = -(p.b1 * s1 + p.b2 * s2)
        val dr = 1.0 + p.a1 * c1 + p.a2 * c2
        val di = -(p.a1 * s1 + p.a2 * s2)
        val mag = sqrt(nr * nr + ni * ni) / sqrt(dr * dr + di * di)
        return (20.0 * log10(mag)).toFloat()
    }

    /** Front+centre at the −3 dB reference never clips before the chain limiter. */
    const val NORMALISE = 1f / (1f + 0.7071f)

    /**
     * Stereo downmix matrix (2 × channelCount), or null when [channelCount] !in 3..8.
     * Android/WAVE PCM channel order; LFE left out (ITU-R BS.775).
     */
    fun downmixMatrix(channelCount: Int, centreGain: Float): Array<FloatArray>? {
        if (channelCount !in 3..8) return null
        val l = FloatArray(channelCount)
        val r = FloatArray(channelCount)
        val s = 0.7071f
        fun both(i: Int, g: Float) {
            l[i] = g
            r[i] = g
        }
        // FL FR always present.
        l[0] = 1f
        r[1] = 1f
        when (channelCount) {
            3 -> both(2, centreGain) // FL FR FC
            4 -> { // FL FR BL BR
                l[2] = s
                r[3] = s
            }
            5 -> { // FL FR FC BL BR
                both(2, centreGain)
                l[3] = s
                r[4] = s
            }
            6 -> { // FL FR FC LFE BL BR
                both(2, centreGain)
                // LFE stays 0
                l[4] = s
                r[5] = s
            }
            7 -> { // FL FR FC LFE BL BR BC: Android's 6.1 (5.1 | BACK_CENTER, in channel-mask bit order)
                both(2, centreGain)
                l[4] = s
                r[5] = s
                both(6, 0.5f)
            }
            8 -> { // FL FR FC LFE BL BR SL SR
                both(2, centreGain)
                l[4] = s
                r[5] = s
                l[6] = s
                r[7] = s
            }
        }
        for (i in 0 until channelCount) {
            l[i] *= NORMALISE
            r[i] *= NORMALISE
        }
        return arrayOf(l, r)
    }

    const val DELAY_STEP_MS = 50
    const val DELAY_MAX_MS = 1000

    /** A/V delay snapped to [DELAY_STEP_MS], clamped to ±[DELAY_MAX_MS]. */
    fun stepDelay(currentMs: Int, steps: Int): Int {
        val raw = currentMs + steps * DELAY_STEP_MS
        val snapped = (raw.toFloat() / DELAY_STEP_MS).roundToInt() * DELAY_STEP_MS
        return snapped.coerceIn(-DELAY_MAX_MS, DELAY_MAX_MS)
    }

    /** Sample-frame delay for [delayMs] at [sampleRate] (sign kept). */
    fun delayFrames(delayMs: Int, sampleRate: Int): Long =
        delayMs.toLong() * sampleRate / 1000

    /** Human-readable layout label, or null outside 1..8. */
    fun channelLayout(channelCount: Int): String? = when (channelCount) {
        1 -> "Mono"
        2 -> "Stereo"
        3 -> "3.0"
        4 -> "4.0"
        5 -> "5.0"
        6 -> "5.1"
        7 -> "6.1"
        8 -> "7.1"
        else -> null
    }

    /** BassBoost strength 0..1000 for UI level 0..3. */
    fun bassStrength(level: Int): Int = when (level.coerceIn(0, 3)) {
        0 -> 0
        1 -> 333
        2 -> 667
        else -> 1000
    }

    /** Inverse of [bassStrength]; 0 stays 0, else nearest of 1..3. */
    fun bassLevel(strength: Int): Int {
        if (strength <= 0) return 0
        return (1..3).minBy { abs(bassStrength(it) - strength) }
    }
}
