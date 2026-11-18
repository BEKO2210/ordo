/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Integrated loudness after ITU-R BS.1770 (K-weighting, 400 ms blocks with 75 % overlap, absolute gate at -70 LKFS and
 * relative gate 10 LU below the ungated level) plus the sample peak, for files without ReplayGain or R128 tags. The two
 * K-weighting stages are the standard's 48 kHz filters re-derived for any sample rate (high shelf near 1.68 kHz, +4 dB;
 * high-pass near 38 Hz). Calibration as in the standard: a 997 Hz sine at 0 dBFS in one channel reads -3.01 LKFS.
 */
class LoudnessMeter(private val sampleRate: Int, private val channels: Int) {
    private val shelf = Array(channels) { Stage(shelfCoefficients(sampleRate)) }
    private val highPass = Array(channels) { Stage(highPassCoefficients(sampleRate)) }
    // Sums of squares per channel for each 100 ms quarter; a block is four quarters.
    private val quarterFrames = sampleRate / 10
    private val quarter = DoubleArray(channels)
    private var framesInQuarter = 0
    private val lastQuarters = ArrayDeque<DoubleArray>()
    private val blocks = ArrayList<Double>()
    var peak = 0f
        private set

    /** Adds one frame, [frame] holding one sample per channel in -1..1. */
    fun add(frame: FloatArray) {
        for (c in 0 until channels) {
            val x = frame[c]
            if (abs(x) > peak) peak = abs(x)
            val y = highPass[c].process(shelf[c].process(x.toDouble()))
            quarter[c] += y * y
        }
        if (++framesInQuarter < quarterFrames) return
        lastQuarters.addLast(quarter.copyOf())
        quarter.fill(0.0)
        framesInQuarter = 0
        if (lastQuarters.size > 4) lastQuarters.removeFirst()
        if (lastQuarters.size == 4) {
            // Mean square of the 400 ms block, summed over channels (weight 1 for left and right).
            var sum = 0.0
            for (c in 0 until channels) sum += lastQuarters.sumOf { it[c] } / (4.0 * quarterFrames)
            blocks += sum
        }
    }

    /** Integrated loudness in LKFS (= LUFS), or null when there is not one loud enough block (silence, < 400 ms). */
    fun integrated(): Double? {
        val absolute = blocks.filter { loudness(it) > ABSOLUTE_GATE }
        if (absolute.isEmpty()) return null
        val relativeGate = loudness(absolute.average()) - RELATIVE_GATE
        val gated = absolute.filter { loudness(it) > relativeGate }
        if (gated.isEmpty()) return null
        return loudness(gated.average())
    }

    private class Stage(private val c: DoubleArray) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x: Double): Double {
            val y = c[0] * x + c[1] * x1 + c[2] * x2 - c[3] * y1 - c[4] * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }
    }

    companion object {
        private const val ABSOLUTE_GATE = -70.0
        private const val RELATIVE_GATE = 10.0

        /** ReplayGain 2.0 reference: -18 LUFS (docs/AUDIO-PLAYER.md, loudness). */
        const val REFERENCE_LUFS = -18.0

        private fun loudness(meanSquare: Double) = -0.691 + 10 * log10(meanSquare)

        /** b0, b1, b2, a1, a2 of the high shelf (stage 1). */
        internal fun shelfCoefficients(sampleRate: Int): DoubleArray {
            val f0 = 1681.974450955533
            val gain = 3.999843853973347
            val q = 0.7071752369554196
            val k = tan(PI * f0 / sampleRate)
            val vh = 10.0.pow(gain / 20)
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1 + k / q + k * k
            return doubleArrayOf(
                (vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
                2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0
            )
        }

        /** b0, b1, b2, a1, a2 of the RLB high-pass (stage 2). */
        internal fun highPassCoefficients(sampleRate: Int): DoubleArray {
            val f0 = 38.13547087602444
            val q = 0.5003270373238773
            val k = tan(PI * f0 / sampleRate)
            val a0 = 1 + k / q + k * k
            return doubleArrayOf(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        }
    }
}
