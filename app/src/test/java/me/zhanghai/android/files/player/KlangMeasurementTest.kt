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
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Measurements of Ordo's own sound chain (KlangAudioProcessor), the numbers docs/AUDIO-PLAYER.md quotes. Every result
 * is printed as "MEASURE name: value" (in the test report's standard output). The equalizer, bass and clarity run in
 * Android's DynamicsProcessing and BassBoost inside the audio server, not in this chain, so they cannot be measured
 * here; that needs a recording of the device's output.
 */
class KlangMeasurementTest {
    private val rate = 48_000

    private fun measure(name: String, value: String) = println("MEASURE $name: $value")

    /** Runs interleaved 16-bit stereo through a fresh chain set to [settings], in 1024-frame buffers like Media3. */
    private fun chain(samples: ShortArray, settings: AudioSettings, dither: Boolean = true): ShortArray {
        val klang = KlangProcessors(dither)
        klang.apply(settings, headphones = true)
        val processor = klang.processor
        processor.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val output = ShortArray(samples.size)
        var position = 0
        while (position < samples.size) {
            val count = minOf(2_048, samples.size - position)
            val input = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())
            for (i in 0 until count) input.putShort(samples[position + i])
            input.flip()
            processor.queueInput(input)
            val result = processor.getOutput()
            var index = position
            while (result.remaining() >= 2) output[index++] = result.getShort()
            position += count
        }
        return output
    }

    private fun stereo(frames: Int, left: (Int) -> Double, right: (Int) -> Double): ShortArray =
        ShortArray(frames * 2) { i ->
            val value = if (i % 2 == 0) left(i / 2) else right(i / 2)
            (value * 32_768).toInt().coerceIn(-32_768, 32_767).toShort()
        }

    private fun channel(samples: ShortArray, which: Int) = DoubleArray(samples.size / 2) { samples[it * 2 + which] / 32_768.0 }

    private fun db(ratio: Double) = 10 * log10(ratio)

    // ---- 1. Bit transparency ----------------------------------------------------------------------------------------

    @Test
    fun everythingOffIsBitTransparent() {
        val random = Random(1)
        val inputs = mapOf(
            "noise" to ShortArray(96_000) { random.nextInt(-32_768, 32_768).toShort() },
            "sine" to stereo(48_000, { 0.9 * sin(2 * PI * 1_000 * it / rate) }, { 0.9 * sin(2 * PI * 997 * it / rate) }),
            "silence" to ShortArray(96_000)
        )
        // Default settings: every own effect off (the equalizer and levelling live in the audio server, not here).
        for ((name, input) in inputs) {
            assertArrayEquals("$name changed", input, chain(input, AudioSettings(), dither = true))
        }
        measure("bit transparency (all effects off)", "noise, sine and silence bit-identical, no dither")
    }

    // ---- 2. Frequency response ------------------------------------------------------------------------------------

    /** Transfer function H = Σ Y·X* / Σ |X|² over Hann-windowed 4096-point segments, in dB per bin. */
    private fun response(x: DoubleArray, y: DoubleArray): DoubleArray {
        val size = 4_096
        val crossRe = DoubleArray(size / 2)
        val crossIm = DoubleArray(size / 2)
        val auto = DoubleArray(size / 2)
        var start = 0
        while (start + size <= x.size) {
            val xr = DoubleArray(size) { x[start + it] * hann(it, size) }
            val xi = DoubleArray(size)
            val yr = DoubleArray(size) { y[start + it] * hann(it, size) }
            val yi = DoubleArray(size)
            fft(xr, xi)
            fft(yr, yi)
            for (k in 0 until size / 2) {
                crossRe[k] += yr[k] * xr[k] + yi[k] * xi[k]
                crossIm[k] += yi[k] * xr[k] - yr[k] * xi[k]
                auto[k] += xr[k] * xr[k] + xi[k] * xi[k]
            }
            start += size / 2
        }
        return DoubleArray(size / 2) { db((crossRe[it] * crossRe[it] + crossIm[it] * crossIm[it]) / (auto[it] * auto[it])) }
    }

    private fun binOf(hz: Int) = hz * 4_096 / rate

    @Test
    fun processingPathIsFlat() {
        // Width at 100 % changes nothing but sends the signal through the float path, limiter and dither.
        val random = Random(2)
        val input = stereo(rate * 8, { random.nextDouble(-0.25, 0.25) }, { random.nextDouble(-0.25, 0.25) })
        val output = chain(input, AudioSettings(widthEnabled = true, widthPercent = 100))
        val h = response(channel(input, 0), channel(output, 0))
        val deviation = (binOf(20)..binOf(20_000)).maxOf { abs(h[it]) }
        measure("frequency response, processing path, 20 Hz-20 kHz", "±%.4f dB".format(deviation))
        assertTrue(deviation <= 0.1)
    }

    @Test
    fun roomSoundResponse() {
        // Left channel only: what stays left (direct, high shelf) and what crosses to the right ear (low-pass).
        val random = Random(3)
        val input = stereo(rate * 8, { random.nextDouble(-0.25, 0.25) }, { 0.0 })
        for (level in 1..3) {
            val output = chain(input, AudioSettings(crossfeedEnabled = true, crossfeedLevel = level))
            val direct = response(channel(input, 0), channel(output, 0))
            val crossed = response(channel(input, 0), channel(output, 1))
            val row = listOf(100, 1_000, 10_000).joinToString { hz ->
                "%d Hz %.1f/%.1f dB".format(hz, direct[binOf(hz)], crossed[binOf(hz)])
            }
            measure("room sound level $level, direct/crossed", row)
            // The other ear gets the lows and much less of the highs; the bs2b gain keeps the sum near 0 dB.
            assertTrue(crossed[binOf(100)] - crossed[binOf(10_000)] > 10)
        }
    }

    // ---- 3. THD+N and noise floor ---------------------------------------------------------------------------------

    private fun blackmanHarris(i: Int, size: Int): Double {
        val t = 2 * PI * i / (size - 1)
        return 0.35875 - 0.48829 * cos(t) + 0.14128 * cos(2 * t) - 0.01168 * cos(3 * t)
    }

    /** THD+N of the left channel at 1 kHz, from a 16384-point Blackman-Harris spectrum of the last samples, in dB. */
    private fun thdPlusNoise(output: ShortArray): Double {
        val size = 16_384
        val left = channel(output, 0)
        val re = DoubleArray(size) { left[left.size - size + it] * blackmanHarris(it, size) }
        val im = DoubleArray(size)
        fft(re, im)
        val fundamental = 1_000 * size / rate
        var signal = 0.0
        var rest = 0.0
        for (k in (20 * size / rate)..(20_000 * size / rate)) {
            val power = re[k] * re[k] + im[k] * im[k]
            if (abs(k - fundamental) <= 6) signal += power else rest += power
        }
        return db(rest / signal)
    }

    @Test
    fun thdPlusNoiseAt1KhzMinus6Dbfs() {
        val amplitude = 0.5
        val input = stereo(
            rate * 3, { amplitude * sin(2 * PI * 1_000 * it / rate) }, { amplitude * sin(2 * PI * 1_000 * it / rate + PI / 2) }
        )
        val defaults = AudioSettings()
        val cases = linkedMapOf(
            "processing path only (width 100 %)" to defaults.copy(widthEnabled = true, widthPercent = 100),
            "mono" to defaults.copy(mono = true),
            "room sound (medium)" to defaults.copy(crossfeedEnabled = true),
            "stereo width (130 %)" to defaults.copy(widthEnabled = true),
            "reverb (studio, 25 %)" to defaults.copy(reverbEnabled = true),
            "analogue warmth (medium)" to defaults.copy(warmthEnabled = true)
        )
        for ((name, settings) in cases) {
            val value = thdPlusNoise(chain(input, settings))
            measure("THD+N 1 kHz -6 dBFS, $name", "%.1f dB".format(value))
            // Linear effects stay at the 16-bit dither floor; warmth adds harmonics on purpose, but gently.
            if (name.startsWith("analogue")) assertTrue(value < -25) else assertTrue("$name $value", value < -80)
        }
    }

    @Test
    fun noiseFloorWithDither() {
        val output = chain(ShortArray(rate * 2 * 2), AudioSettings(widthEnabled = true, widthPercent = 100))
        val left = channel(output, 0)
        val rms = sqrt(left.sumOf { it * it } / left.size)
        val dbfs = 20 * log10(rms)
        measure("noise floor, digital silence through the processing path", "%.1f dBFS RMS (TPDF dither)".format(dbfs))
        assertTrue(dbfs in -99.0..-93.0)
        // All off: digital silence stays digital silence.
        assertTrue(chain(ShortArray(rate * 2), AudioSettings()).all { it.toInt() == 0 })
    }

    // ---- 4. True peak -----------------------------------------------------------------------------------------------

    /** Pink noise (Paul Kellet's filter), scaled to a sample peak of 0 dBFS. */
    private fun pink(frames: Int, seed: Int): DoubleArray {
        val random = Random(seed)
        var b0 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        val out = DoubleArray(frames) {
            val white = random.nextDouble(-1.0, 1.0)
            b0 = 0.99765 * b0 + white * 0.0990460
            b1 = 0.96300 * b1 + white * 0.2965164
            b2 = 0.57000 * b2 + white * 1.0526913
            b0 + b1 + b2 + white * 0.1848
        }
        val peak = out.maxOf { abs(it) }
        return DoubleArray(frames) { out[it] / peak * 32_767.0 / 32_768.0 }
    }

    /** True peak with 4x oversampling (BS.1770 Annex 2): windowed-sinc interpolation, 64 taps. */
    private fun truePeakDb(signal: DoubleArray): Double {
        val taps = 32
        val kernels = Array(3) { phase ->
            val t = (phase + 1) / 4.0
            DoubleArray(2 * taps) { index ->
                val x = index - taps + 1 - t
                sin(PI * x) / (PI * x) * (0.5 + 0.5 * cos(PI * x / taps))
            }
        }
        var peak = signal.maxOf { abs(it) }
        for (n in taps until signal.size - taps) {
            for (kernel in kernels) {
                var sum = 0.0
                for (index in kernel.indices) sum += signal[n + index - taps + 1] * kernel[index]
                peak = maxOf(peak, abs(sum))
            }
        }
        return 20 * log10(peak)
    }

    @Test
    fun truePeakStaysBelowMinus1DbtpWithEveryEffectOn() {
        val frames = rate * 4
        val left = pink(frames, 4)
        val right = pink(frames, 5)
        val input = stereo(frames, { left[it] }, { right[it] })
        val all = AudioSettings(
            crossfeedEnabled = true, crossfeedLevel = 3, widthEnabled = true, widthPercent = 150,
            warmthEnabled = true, warmthLevel = 3, reverbEnabled = true, reverbPreset = ReverbPreset.HALL, reverbMix = 100
        )
        val output = chain(input, all)
        val peaks = (0..1).map { truePeakDb(channel(output, it)) }
        measure("true peak, 0 dBFS pink noise, every own effect at its strongest", "%.2f dBTP".format(peaks.max()))
        assertTrue(peaks.all { it <= -1.0 })
    }

    // ---- 5. Latency -----------------------------------------------------------------------------------------------

    @Test
    fun theChainAddsNoLatency() {
        val input = ShortArray(4_096 * 2).also { it[1_000 * 2] = 16_000; it[1_000 * 2 + 1] = 16_000 }
        for ((name, settings) in listOf(
            "processing path" to AudioSettings(widthEnabled = true, widthPercent = 100),
            "every effect but reverb" to AudioSettings(
                crossfeedEnabled = true, widthEnabled = true, warmthEnabled = true, mono = false
            )
        )) {
            val output = chain(input, settings, dither = false)
            val left = channel(output, 0)
            val at = left.indices.maxByOrNull { abs(left[it]) }!!
            measure("latency, $name", "${at - 1_000} samples")
            assertEquals(1_000, at)
        }
    }

    private fun hann(i: Int, size: Int) = 0.5 - 0.5 * cos(2 * PI * i / size)

    /** In-place radix-2 FFT. */
    private fun fft(real: DoubleArray, imag: DoubleArray) {
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
                var t = real[i]
                real[i] = real[j]
                real[j] = t
                t = imag[i]
                imag[i] = imag[j]
                imag[j] = t
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2 * PI / length
            var start = 0
            while (start < n) {
                for (k in 0 until length / 2) {
                    val cr = cos(angle * k)
                    val ci = sin(angle * k)
                    val a = start + k
                    val b = a + length / 2
                    val tr = real[b] * cr - imag[b] * ci
                    val ti = real[b] * ci + imag[b] * cr
                    real[b] = real[a] - tr
                    imag[b] = imag[a] - ti
                    real[a] += tr
                    imag[a] += ti
                }
                start += length
            }
            length = length shl 1
        }
    }
}
