/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random

/**
 * EBU R 128 s2-2023 playback targets: "Original" -23 LUFS and "On the go" -16 LUFS, reached through the true-peak
 * limiter's input gain, ±0.5 LU, at most -1 dBTP, on music-like material (beats, bass, chords, noise) with dynamics.
 */
class LoudnessTargetTest {
    private val rate = 48_000

    /** 30 s: kick and snare every 0.5 s, a bass line, three-note chords, a quieter middle part; about -30 LUFS. */
    private fun material(): Array<FloatArray> {
        val frames = rate * 30
        val random = Random(5)
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        val chords = listOf(listOf(220.0, 277.2, 329.6), listOf(196.0, 246.9, 293.7), listOf(174.6, 220.0, 261.6))
        for (n in 0 until frames) {
            val t = n.toDouble() / rate
            val beat = t % 0.5
            val kick = 0.5 * exp(-beat * 30) * sin(2 * PI * 55 * beat)
            val snare = if ((t % 1.0) >= 0.5) 0.15 * exp(-beat * 40) * random.nextDouble(-1.0, 1.0) else 0.0
            val chord = chords[(t / 2).toInt() % chords.size]
            val pad = chord.sumOf { 0.04 * sin(2 * PI * it * t) }
            val bass = 0.12 * sin(2 * PI * chord[0] / 4 * t)
            val section = if (t in 12.0..18.0) 0.4 else 1.0 // a quiet bridge
            val common = (kick + bass + snare) * section
            left[n] = ((common + pad * section) * 0.25).toFloat()
            right[n] = ((common + pad * section * cos(0.3)) * 0.25).toFloat()
        }
        return arrayOf(left, right)
    }

    private fun measure(left: FloatArray, right: FloatArray, from: Int = 0): Double {
        val meter = LoudnessMeter(rate, 2)
        val frame = FloatArray(2)
        for (n in from until left.size) {
            frame[0] = left[n]
            frame[1] = right[n]
            meter.add(frame)
        }
        return meter.integrated()!!
    }

    private fun limit(input: Array<FloatArray>, gainDb: Float): Array<FloatArray> {
        val limiter = TruePeakLimiter().apply { this.gainDb = gainDb }
        limiter.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        limiter.flush()
        val left = ArrayList<Float>()
        val right = ArrayList<Float>()
        fun drain() {
            val output = limiter.output
            while (output.hasRemaining()) {
                left += output.getShort() / 32_768f
                right += output.getShort() / 32_768f
            }
        }
        val frames = input[0].size
        var position = 0
        while (position < frames) {
            val count = minOf(1_024, frames - position)
            val buffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until count) {
                buffer.putShort((input[0][position + i] * 32_767).toInt().toShort())
                buffer.putShort((input[1][position + i] * 32_767).toInt().toShort())
            }
            buffer.flip()
            limiter.queueInput(buffer)
            drain()
            position += count
        }
        limiter.queueEndOfStream()
        drain()
        return arrayOf(left.toFloatArray(), right.toFloatArray())
    }

    private fun truePeakDb(signal: FloatArray): Double {
        val taps = 32
        var peak = signal.maxOf { abs(it) }.toDouble()
        for (phase in 0 until 3) {
            val t = (phase + 1) / 4.0
            val kernel = DoubleArray(2 * taps) { index ->
                val x = index - taps + 1 - t
                sin(PI * x) / (PI * x) * (0.5 + 0.5 * cos(PI * x / taps))
            }
            for (n in taps until signal.size - taps) {
                var sum = 0.0
                for (index in kernel.indices) sum += signal[n + index - taps + 1] * kernel[index]
                peak = maxOf(peak, abs(sum))
            }
        }
        return 20 * log10(peak)
    }

    @Test
    fun gainMathAgainstTheReplayGainReference() {
        // A file at -30 LUFS has a ReplayGain-style gain of +12 dB to -18 LUFS.
        val info = ReplayGainInfo(12f)
        assertEquals(7f, LoudnessTargets.gainDb(info, LoudnessTarget.ORIGINAL, 0f), 1e-4f)
        assertEquals(14f, LoudnessTargets.gainDb(info, LoudnessTarget.ON_THE_GO, 0f), 1e-4f)
        assertEquals(15f, LoudnessTargets.gainDb(info, LoudnessTarget.ON_THE_GO, 1f), 1e-4f)
        assertEquals(0f, LoudnessTargets.gainDb(AudioSettings(normalizationEnabled = false), info), 0f)
        assertEquals(0f, LoudnessTargets.gainDb(AudioSettings(normalizationEnabled = true), null), 0f)
        val settings = AudioSettings(normalizationEnabled = true, loudnessTarget = LoudnessTarget.ON_THE_GO)
        assertEquals(LoudnessTarget.ON_THE_GO, AudioSettingsCodec.decode(AudioSettingsCodec.encode(settings)).loudnessTarget)
    }

    @Test
    fun targetsAreHeldWithinHalfAnLuAndTruePeakStaysBelowMinus1() {
        val input = material()
        val inputLufs = measure(input[0], input[1])
        val info = ReplayGainInfo((LoudnessMeter.REFERENCE_LUFS - inputLufs).toFloat())
        for (target in LoudnessTarget.entries) {
            val output = limit(input, LoudnessTargets.gainDb(info, target, 0f))
            val lufs = measure(output[0], output[1])
            val peak = maxOf(truePeakDb(output[0]), truePeakDb(output[1]))
            println("loudness target %s: in %.2f LUFS, out %.2f LUFS, %.2f dBTP".format(target, inputLufs, lufs, peak))
            assertEquals(target.name, target.lufs.toDouble(), lufs, 0.5)
            assertTrue("${target.name}: %.2f dBTP".format(peak), peak <= -1.0)
        }
    }
}
