/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The true-peak limiter (−1 dBTP, 4x oversampled) on signals whose peaks lie between the samples, and BS.1770 gating
 * against EBU Tech 3341's test cases.
 */
class TruePeakLimiterTest {
    private val rate = 48_000

    /** Runs interleaved stereo (−1..1) through a limiter in 1024-frame buffers and drains it. */
    private fun limit(signal: DoubleArray, armed: Boolean = true): ShortArray {
        val limiter = TruePeakLimiter().apply { this.armed = armed }
        limiter.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        limiter.flush()
        val out = ArrayList<Short>()
        fun drain() {
            val output = limiter.output
            while (output.hasRemaining()) out += output.getShort()
        }
        var position = 0
        while (position < signal.size) {
            val count = minOf(2_048, signal.size - position)
            val input = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())
            for (i in 0 until count) {
                input.putShort((signal[position + i] * 32_767).toInt().coerceIn(-32_768, 32_767).toShort())
            }
            input.flip()
            limiter.queueInput(input)
            drain()
            position += count
        }
        limiter.queueEndOfStream()
        drain()
        return out.toShortArray()
    }

    private fun stereo(frames: Int, value: (Int) -> Double) = DoubleArray(frames * 2) { value(it / 2) }

    /** True peak after BS.1770 Annex 2: 4x oversampling, 64-tap windowed-sinc interpolation (KlangMeasurementTest). */
    private fun truePeakDb(samples: ShortArray): Double {
        val taps = 32
        val kernels = Array(3) { phase ->
            val t = (phase + 1) / 4.0
            DoubleArray(2 * taps) { index ->
                val x = index - taps + 1 - t
                sin(PI * x) / (PI * x) * (0.5 + 0.5 * cos(PI * x / taps))
            }
        }
        var peak = 0.0
        for (channel in 0..1) {
            val signal = DoubleArray(samples.size / 2) { samples[it * 2 + channel] / 32_768.0 }
            peak = maxOf(peak, signal.maxOf { abs(it) })
            for (n in taps until signal.size - taps) {
                for (kernel in kernels) {
                    var sum = 0.0
                    for (index in kernel.indices) sum += signal[n + index - taps + 1] * kernel[index]
                    peak = maxOf(peak, abs(sum))
                }
            }
        }
        return 20 * log10(peak)
    }

    /** White noise low-passed at 0.42 fs (20 kHz at 48 kHz) with a 255-tap windowed sinc, normalised to 0 dBFS. */
    private fun bandLimitedNoise(frames: Int, seed: Int): DoubleArray {
        val random = Random(seed)
        val white = DoubleArray(frames + 255) { random.nextDouble(-1.0, 1.0) }
        val kernel = DoubleArray(255) { j ->
            val x = j - 127.0
            val sinc = if (x == 0.0) 0.84 else sin(PI * 0.84 * x) / (PI * x)
            sinc * (0.42 + 0.5 * cos(PI * x / 127) + 0.08 * cos(2 * PI * x / 127))
        }
        val out = DoubleArray(frames) { n -> kernel.indices.sumOf { white[n + it] * kernel[it] } }
        val peak = out.maxOf { abs(it) }
        return DoubleArray(frames) { out[it] / peak }
    }

    @Test
    fun intersamplePeaksStayAtOrBelowMinus1Dbtp() {
        val frames = rate / 2
        val signals = mapOf(
            // fs/4 at 45°: every sample at ±0.707 of the wave, its peak 3 dB above them, full scale.
            "fs/4 at 45 degrees" to stereo(frames) { sin(PI / 2 * it + PI / 4) / sin(PI / 4) * 0.999 },
            "0.3 fs sine" to stereo(frames) { 0.999 * sin(2 * PI * 0.3 * it + 0.3) },
            "0.45 fs sine" to stereo(frames) { 0.999 * sin(2 * PI * 0.45 * it + 0.3) },
            "square" to stereo(frames) { if (sin(2 * PI * 0.01 * it) >= 0) 0.999 else -0.999 },
            "band-limited noise" to bandLimitedNoise(frames, 7).let { n -> stereo(frames) { n[it] * 0.999 } },
            "clipped band-limited noise" to bandLimitedNoise(frames, 9).let { n ->
                stereo(frames) { (n[it] * 3).coerceIn(-0.999, 0.999) }
            }
        )
        for ((name, signal) in signals) {
            val input = truePeakDb(ShortArray(signal.size) { (signal[it] * 32_767).toInt().toShort() })
            val output = truePeakDb(limit(signal))
            println("true peak, %s: %.2f dBTP in, %.2f dBTP out".format(name, input, output))
            assertTrue("$name: %.3f dBTP".format(output), output <= -1.0)
        }
    }

    @Test
    fun keepsLengthAndDelaysByItsLatencyOnly() {
        val frames = 5_000
        val signal = stereo(frames) { 0.1 * sin(2 * PI * 440 * it / rate) }
        val out = limit(signal)
        assertEquals(frames * 2, out.size)
        // Quiet sound is never touched: bit for bit, armed or not.
        val input = ShortArray(signal.size) { (signal[it] * 32_767).toInt().toShort() }
        assertArrayEquals(input, out)
        assertArrayEquals(input, limit(signal, armed = false))
    }

    @Test
    fun unarmedItLeavesLoudSoundUntouched() {
        val signal = stereo(4_000) { sin(PI / 2 * it + PI / 4) / sin(PI / 4) * 0.999 }
        val input = ShortArray(signal.size) { (signal[it] * 32_767).toInt().toShort() }
        assertArrayEquals(input, limit(signal, armed = false))
    }

    @Test
    fun cpuOfTheLimiter() {
        val signal = bandLimitedNoise(rate, 3).let { n -> stereo(rate) { n[it] * 0.999 } }
        repeat(3) { limit(signal) }
        val runs = 10
        val start = System.nanoTime()
        repeat(runs) { limit(signal) }
        val share = (System.nanoTime() - start) / (runs * 1e9) * 100
        println("True-peak limiter, 48 kHz stereo, limiting: %.2f %% of one core (JVM, incl. test conversion)".format(share))
        assertTrue(share < 25)
    }

    // ---- BS.1770 gating, EBU Tech 3341 (2016) test cases 1-5, 1 kHz stereo sines ---------------------------------

    /** Segments of (seconds, dBFS in both channels) measured as one programme. */
    private fun programme(vararg segments: Pair<Double, Double>): Double {
        val meter = LoudnessMeter(rate, 2)
        val frame = FloatArray(2)
        var n = 0
        for ((seconds, dbfs) in segments) {
            val amplitude = 10.0.pow(dbfs / 20)
            repeat((seconds * rate).toInt()) {
                val value = (amplitude * sin(2 * PI * 1_000 * n / rate)).toFloat()
                frame[0] = value
                frame[1] = value
                meter.add(frame)
                n++
            }
        }
        return meter.integrated()!!
    }

    @Test
    fun tech3341GatingCases() {
        // Case 1 and 2: steady tones.
        assertEquals(-23.0, programme(20.0 to -23.0), 0.1)
        assertEquals(-33.0, programme(20.0 to -33.0), 0.1)
        // Case 3: the relative gate removes the quieter start and end.
        assertEquals(-23.0, programme(10.0 to -36.0, 60.0 to -23.0, 10.0 to -36.0), 0.1)
        // Case 4: the absolute gate removes -72 dBFS, the relative gate -36.
        assertEquals(
            -23.0, programme(10.0 to -72.0, 10.0 to -36.0, 60.0 to -23.0, 10.0 to -36.0, 10.0 to -72.0), 0.1
        )
        // Case 5: no quieter part is gated out; their mix reads -23.
        assertEquals(-23.0, programme(20.0 to -26.0, 20.1 to -20.0, 20.0 to -26.0), 0.1)
    }
}
