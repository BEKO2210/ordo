/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Fill in missing highs" for heavily compressed files (docs/AUDIO-QUALITAET-RECHERCHE.md, 2): nothing comes back that
 * the encoder cut away, this only adds plausible, quiet overtones above the cut. It acts only when [CutoffDetector] finds
 * a cut below 18 kHz; then a band under the cut (high-pass at half the cut frequency) goes through a gentle curve that
 * makes overtones, a second high-pass (12 dB/octave, twice) keeps only what lies above the cut, and that is mixed in at
 * most -24 dB. With no cut found it adds nothing at all. Classic harmonic excitation, own code.
 */
class HighsStage : KlangStage() {
    /** 1 light (-30 dB), 2 medium (-24 dB). */
    @Volatile
    var level: Int = 1

    /** Last cut the detector found, in Hz; 0 while there is none (then the stage adds nothing). */
    @Volatile
    var detectedCutoffHz: Int = 0
        private set

    private val detector = CutoffDetector()
    private var cutoff = 0
    private var amount = 0f
    private var targetAmount = 0f
    private val bandLeft = Biquad()
    private val bandRight = Biquad()
    private val topLeft = Biquad()
    private val topRight = Biquad()
    private val topLeft2 = Biquad()
    private val topRight2 = Biquad()

    override fun onSampleRate(sampleRate: Int) {
        detector.reset(sampleRate)
        detectedCutoffHz = 0
    }

    override fun clear() {
        listOf(bandLeft, bandRight, topLeft, topRight, topLeft2, topRight2).forEach { it.clear() }
        detector.reset(sampleRate)
        detectedCutoffHz = 0
        cutoff = 0
        amount = 0f
    }

    override fun prepare() {
        val found = detectedCutoffHz
        if (found != cutoff && found > 0) {
            cutoff = found
            bandLeft.highPass(sampleRate, cutoff / 2.0)
            bandRight.highPass(sampleRate, cutoff / 2.0)
            for (filter in arrayOf(topLeft, topRight, topLeft2, topRight2)) filter.highPass(sampleRate, cutoff.toDouble())
        }
        targetAmount = if (found > 0) LEVEL_GAINS[level.coerceIn(1, LEVEL_GAINS.size) - 1] else 0f
    }

    override fun processFrame(left: Float, right: Float) {
        detector.add((left + right) * 0.5f)?.let { detectedCutoffHz = it }
        amount += (targetAmount - amount) * glide
        if (cutoff == 0 || amount < 1e-6f) {
            outLeft = left
            outRight = right
            return
        }
        outLeft = left + amount * excite(left, bandLeft, topLeft, topLeft2)
        outRight = right + amount * excite(right, bandRight, topRight, topRight2)
    }

    // x·|x| makes even and odd overtones of the band below the cut; the high-passes keep those above it.
    private fun excite(x: Float, band: Biquad, top: Biquad, top2: Biquad): Float {
        val high = band.process(x)
        return top2.process(top.process(high * abs(high) * EXCITE_DRIVE))
    }

    /** Second-order filter (RBJ cookbook), direct form I. */
    internal class Biquad {
        private var b0 = 1.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun highPass(sampleRate: Int, frequency: Double) {
            val w = 2 * PI * frequency.coerceAtMost(sampleRate * 0.45) / sampleRate
            val alpha = sin(w) / (2 * Q)
            val cosW = cos(w)
            val a0 = 1 + alpha
            b0 = (1 + cosW) / 2 / a0
            b1 = -(1 + cosW) / a0
            b2 = (1 + cosW) / 2 / a0
            a1 = -2 * cosW / a0
            a2 = (1 - alpha) / a0
        }

        fun clear() {
            x1 = 0.0
            x2 = 0.0
            y1 = 0.0
            y2 = 0.0
        }

        fun process(input: Float): Float {
            val x = input.toDouble()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = if (abs(y) < 1e-30) 0.0 else y
            return y.toFloat()
        }

        private companion object {
            /** Butterworth. */
            val Q = 1 / sqrt(2.0)
        }
    }

    companion object {
        /** -30 dB and -24 dB: the research's ceiling is -24 dB, and there is deliberately no "strong". */
        private val LEVEL_GAINS = floatArrayOf(0.0316f, 0.0631f)
        /** x·|x| of a quiet band is tiny; this brings the overtones to about the band's own level before the gain. */
        private const val EXCITE_DRIVE = 4f
    }
}

/**
 * Finds the frequency above which a file has (almost) nothing: the low-pass every lossy encoder sets, for example LAME at
 * 17 kHz for 128 kbit/s. It averages the spectrum of the mono mix over about two seconds (2048-point FFT, Hann window)
 * and reports a cut where the level falls by more than [DROP_DB] within [DROP_WIDTH_HZ] and stays down up to 20 kHz.
 * Cuts at 18 kHz and above (good encoders, lossless files) and below [MIN_CUT_HZ] (speech, old recordings) count as none.
 */
internal class CutoffDetector {
    private val window = FloatArray(SIZE) { (0.5 - 0.5 * cos(2 * PI * it / SIZE)).toFloat() }
    private val samples = FloatArray(SIZE)
    private val real = DoubleArray(SIZE)
    private val imag = DoubleArray(SIZE)
    private val power = DoubleArray(SIZE / 2)
    private var filled = 0
    private var blocks = 0
    private var blocksPerAverage = 1
    private var sampleRate = KlangAudioProcessor.DEFAULT_SAMPLE_RATE

    fun reset(sampleRate: Int) {
        this.sampleRate = sampleRate
        blocksPerAverage = maxOf(1, (sampleRate * AVERAGE_SECONDS / SIZE).toInt())
        filled = 0
        blocks = 0
        power.fill(0.0)
    }

    /** Adds one sample; every two seconds returns the cut found (Hz) or 0 for none, otherwise null. */
    fun add(sample: Float): Int? {
        samples[filled++] = sample
        if (filled < SIZE) return null
        filled = 0
        for (i in 0 until SIZE) {
            real[i] = (samples[i] * window[i]).toDouble()
            imag[i] = 0.0
        }
        fft(real, imag)
        for (bin in power.indices) power[bin] += real[bin] * real[bin] + imag[bin] * imag[bin]
        if (++blocks < blocksPerAverage) return null
        val cut = findCut(power, sampleRate)
        blocks = 0
        power.fill(0.0)
        return cut
    }

    companion object {
        internal const val SIZE = 2048
        private const val AVERAGE_SECONDS = 2.0
        internal const val MIN_CUT_HZ = 11_000
        internal const val MAX_CUT_HZ = 18_000
        private const val DROP_DB = 30.0
        private const val DROP_WIDTH_HZ = 500.0
        private const val TOP_HZ = 20_000.0

        /** The cut in Hz, or 0 when the spectrum has none between [MIN_CUT_HZ] and [MAX_CUT_HZ]. */
        internal fun findCut(power: DoubleArray, sampleRate: Int): Int {
            val binHz = sampleRate.toDouble() / SIZE
            fun db(bin: Int) = 10 * ln(power[bin] + 1e-20) / ln(10.0)
            val width = maxOf(1, (DROP_WIDTH_HZ / binHz).toInt())
            val top = minOf(power.size - 1, (TOP_HZ / binHz).toInt())
            // Nothing playing (silence, dither): no verdict.
            val reference = (power.indices).filter { it * binHz in 1_000.0..8_000.0 }.maxOfOrNull { db(it) } ?: return 0
            if (reference < -60) return 0
            var bin = (MIN_CUT_HZ / binHz).toInt()
            while (bin + width <= top) {
                val before = db(bin)
                if (before > reference - 60) {
                    // Everything from here to 20 kHz must lie DROP_DB below the level just under the cut.
                    val restMax = (bin + width..top).maxOf { db(it) }
                    if (before - restMax > DROP_DB) {
                        val hz = (bin * binHz).toInt()
                        return if (hz < MAX_CUT_HZ) hz else 0
                    }
                }
                bin++
            }
            return 0
        }

        /** In-place radix-2 FFT. */
        internal fun fft(real: DoubleArray, imag: DoubleArray) {
            val n = real.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    var t = real[i]; real[i] = real[j]; real[j] = t
                    t = imag[i]; imag[i] = imag[j]; imag[j] = t
                }
            }
            var length = 2
            while (length <= n) {
                val angle = -2 * PI / length
                val wReal = cos(angle)
                val wImag = sin(angle)
                var start = 0
                while (start < n) {
                    var cReal = 1.0
                    var cImag = 0.0
                    for (k in 0 until length / 2) {
                        val a = start + k
                        val b = a + length / 2
                        val tReal = real[b] * cReal - imag[b] * cImag
                        val tImag = real[b] * cImag + imag[b] * cReal
                        real[b] = real[a] - tReal
                        imag[b] = imag[a] - tImag
                        real[a] += tReal
                        imag[a] += tImag
                        val next = cReal * wReal - cImag * wImag
                        cImag = cReal * wImag + cImag * wReal
                        cReal = next
                    }
                    start += length
                }
                length = length shl 1
            }
        }
    }
}
