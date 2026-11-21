/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * HDR to SDR for screens that can't show HDR, without Android in it (unit-tested in HdrToneMapTest); the shader in
 * [HdrToneMapShader] gets the curve and the output step as tables from here ([curveTable], [outputTable]).
 *
 * The curve is the EETF of ITU-R BT.2408-9 Annex 5 (until BT.2390-8 it stood in BT.2390): in the PQ domain,
 * normalised to the source's range, 1:1 below a knee, above it a Hermite spline that starts with slope 1 and ends
 * exactly at the target's peak. The source's peak is not the mastering metadata but what the picture really
 * holds, measured per frame and smoothed per scene ([SceneTracker]); the idea of measuring it comes from
 * libplacebo's peak detection (mpv), the code here is Ordo's own. The target is the screen's peak brightness
 * ([target]). BT.2408's black lift is left out (an SDR screen's black is its own).
 *
 * Two changes to the annex, after the picture panel (21.11.2026) saw highlights crushed: the knee sits lower,
 * KS = (1 + k) · maxLum − k with k = [KNEE_OFFSET] = 1 instead of 0.5 (on 540 cd/m² from a 1083 peak: 266 instead
 * of 380 cd/m², reference white 203 stays 1:1), and the spline ends with slope [END_SLOPE] = 0.75 instead of 0 (an
 * added (t³ − t²) term; 1 made the shoulder S-shaped in the panel's second round). On that scene the grey ramp
 * 400…1000 cd/m² then spreads over 211…249 (8 bit) instead of 222…251 with 248–255 merged, and 900/950/1000 lie 3–4
 * codes apart instead of 0. Monotonic and never above the target for every maxLum ≥ 0.5 (targets ≥ 100 cd/m²): the
 * start and end slopes are 2 and 1.5 times the secant, inside Fritsch–Carlson's circle (2² + 1.5² ≤ 9); the annex
 * itself sits on its edge (3 and 0).
 */
object HdrToneMap {
    /** HDR reference white, BT.2408-9 § 2.1: 203 cd/m², 58 % PQ, 75 % HLG. */
    const val REFERENCE_WHITE = 203.0
    /** Before the first frame is measured (the usual mastering peak, BT.2408). */
    const val DEFAULT_PEAK = 1000.0
    /** The annex has 0.5. */
    const val KNEE_OFFSET = 1.0
    /** The annex has 0. */
    const val END_SLOPE = 0.75
    /** The pass may cost this much GPU time per frame on average; above it Android's own mapping takes over. */
    const val MAX_GPU_MS = 2.0

    private const val M1 = 2610.0 / 16384.0
    private const val M2 = 2523.0 / 4096.0 * 128.0
    private const val C1 = 3424.0 / 4096.0
    private const val C2 = 2413.0 / 4096.0 * 32.0
    private const val C3 = 2392.0 / 4096.0 * 32.0

    /** SMPTE ST 2084 inverse EOTF: cd/m² to the PQ signal, 0 to 1. */
    fun pq(nits: Double): Double {
        val y = (nits / 10_000.0).coerceIn(0.0, 1.0).pow(M1)
        return ((C1 + C2 * y) / (1 + C3 * y)).pow(M2)
    }

    /** SMPTE ST 2084 EOTF: the PQ signal to cd/m². */
    fun pqToNits(signal: Double): Double {
        val p = signal.coerceIn(0.0, 1.0).pow(1 / M2)
        return 10_000.0 * (max(p - C1, 0.0) / (C2 - C3 * p)).pow(1 / M1)
    }

    /**
     * BT.2408-9 Annex 5 on one value: [nits] of a source whose brightest part is [sourcePeak] onto a screen that
     * reaches [targetPeak], both in cd/m². A source that fits stays as it is; what lies above the measured peak
     * (it can, between measurements) ends at the target's peak. [kneeOffset] 0.5 and [endSlope] 0 are the annex as
     * written.
     */
    fun eetf(
        nits: Double,
        sourcePeak: Double,
        targetPeak: Double,
        kneeOffset: Double = KNEE_OFFSET,
        endSlope: Double = END_SLOPE
    ): Double {
        val black = pq(0.0)
        val range = pq(sourcePeak) - black
        val maxLum = (pq(targetPeak) - black) / range
        if (maxLum >= 1.0) return nits
        val e1 = ((pq(nits) - black) / range).coerceIn(0.0, 1.0)
        val ks = knee(maxLum, kneeOffset)
        val e2 = if (e1 < ks) e1 else hermite(e1, ks, maxLum, endSlope)
        return pqToNits(e2 * range + black)
    }

    /** Where the curve leaves 1:1, in cd/m²; infinite when the scene fits the screen. */
    fun kneeNits(sourcePeak: Double, targetPeak: Double): Double {
        val black = pq(0.0)
        val range = pq(sourcePeak) - black
        val maxLum = (pq(targetPeak) - black) / range
        return if (maxLum >= 1.0) Double.POSITIVE_INFINITY else pqToNits(knee(maxLum) * range + black)
    }

    /**
     * The curve above the knee for the shader, over the target: entry i is [eetf] at knee + (i / (size − 1))² ·
     * (peak − knee). Squared spacing puts more entries just above the knee, where the curve bends most.
     */
    fun curveTable(sourcePeak: Double, targetPeak: Double, size: Int): FloatArray {
        val knee = kneeNits(sourcePeak, targetPeak)
        return FloatArray(size) {
            val u = it / (size - 1.0)
            (eetf(knee + u * u * (sourcePeak - knee), sourcePeak, targetPeak) / targetPeak).toFloat()
        }
    }

    /** The knee KS in the normalised PQ domain. */
    fun knee(maxLum: Double, kneeOffset: Double = KNEE_OFFSET): Double =
        ((1 + kneeOffset) * maxLum - kneeOffset).coerceAtLeast(0.0)

    private fun hermite(e: Double, ks: Double, maxLum: Double, endSlope: Double): Double {
        val t = (e - ks) / (1 - ks)
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * ks + (t3 - 2 * t2 + t) * (1 - ks) + (-2 * t3 + 3 * t2) * maxLum +
            (t3 - t2) * (1 - ks) * endSlope
    }

    /**
     * The screen's peak in cd/m² (Display.HdrCapabilities' desired maximum luminance). SDR screens often report
     * nothing useful (0, or Android's placeholder far off): outside 100–2000 the HDR reference white stands in, so
     * diffuse white lands on the screen's white.
     */
    fun target(displayMaxLuminance: Float): Double =
        displayMaxLuminance.toDouble().takeIf { it in 100.0..2000.0 } ?: REFERENCE_WHITE

    /**
     * The measured peak of one frame from the block maxima of the downscaled frame (their PQ values): the
     * [PERCENTILE] of a [BINS]-bin histogram, so a handful of hot pixels does not decide the scene.
     */
    fun framePeak(blockPeaks: DoubleArray): Double {
        if (blockPeaks.isEmpty()) return pq(DEFAULT_PEAK)
        val histogram = IntArray(BINS)
        for (value in blockPeaks) histogram[(value.coerceIn(0.0, 1.0) * (BINS - 1)).toInt()]++
        val above = ((1 - PERCENTILE) * blockPeaks.size).toInt()
        var count = 0
        for (bin in BINS - 1 downTo 0) {
            count += histogram[bin]
            if (count > above) return ((bin + 1.0) / (BINS - 1)).coerceAtMost(1.0)
        }
        return 0.0
    }

    /**
     * The pass's last step as a table over u = √(SDR light), 0 to 1: gamma 2.2 gives the SDR code, and the entry is
     * the light (Media3's scale, 1.0 = 1000 cd/m²) whose PQ code Media3's final step writes is exactly that code
     * (the surface is SDR, see HdrGraph). Indexed by the square root so the dark end, where PQ is steep, gets more
     * entries: with 1024 half floats and linear filtering it stays within one 10-bit code (checked in the test).
     */
    fun outputTable(size: Int): FloatArray = FloatArray(size) {
        val u = it / (size - 1.0)
        (pqToNits((u * u).pow(1 / 2.2)) / 1000.0).toFloat()
    }

    /**
     * One second of GPU times of the pass: too slow when it averaged more than [MAX_GPU_MS] over at least
     * [MIN_FRAMES] frames once playback has settled (the first frames compile shaders).
     */
    fun tooSlow(meanMs: Double, frames: Int, steady: Boolean): Boolean =
        steady && frames >= MIN_FRAMES && meanMs > MAX_GPU_MS

    private const val MIN_FRAMES = 20
    private const val BINS = 1024
    private const val PERCENTILE = 0.999

    /**
     * The peak per scene: a frame's measurement moves the smoothed values by 1/[SMOOTHING_FRAMES] (no flicker
     * from frame to frame); when the frame's average brightness jumps by more than [CUT_HIGH] in PQ the scene changed
     * and the new frame counts at once. Between [CUT_LOW] and [CUT_HIGH] the step grows linearly, so a fast pan
     * follows sooner than a still shot (thresholds as in libplacebo's defaults, 1 % and 3 % PQ).
     */
    class SceneTracker {
        var peak = Double.NaN
            private set
        private var average = Double.NaN
        /** How many scene cuts were seen (the signal path and tests read it). */
        var cuts = 0
            private set

        /** Takes one frame's peak and average (PQ, 0 to 1) and returns the smoothed peak in PQ. */
        fun update(framePeak: Double, frameAverage: Double): Double {
            if (peak.isNaN()) {
                peak = framePeak
                average = frameAverage
                return peak
            }
            val jump = abs(frameAverage - average)
            val base = 1.0 / SMOOTHING_FRAMES
            val weight = when {
                jump >= CUT_HIGH -> 1.0.also { cuts++ }
                jump <= CUT_LOW -> base
                else -> base + (1 - base) * (jump - CUT_LOW) / (CUT_HIGH - CUT_LOW)
            }
            peak += (framePeak - peak) * weight
            average += (frameAverage - average) * weight
            return peak
        }

        companion object {
            const val SMOOTHING_FRAMES = 20
            const val CUT_LOW = 0.01
            const val CUT_HIGH = 0.03
        }
    }
}
