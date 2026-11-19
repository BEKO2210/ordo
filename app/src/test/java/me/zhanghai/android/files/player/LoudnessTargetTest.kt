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

    /** BS.1770 integrated loudness of all channels (weight 1 each, the same meter for input and output). */
    private fun measure(channels: Array<FloatArray>): Double {
        val meter = LoudnessMeter(rate, channels.size)
        val frame = FloatArray(channels.size)
        for (n in channels[0].indices) {
            for (c in channels.indices) frame[c] = channels[c][n]
            meter.add(frame)
        }
        return meter.integrated()!!
    }

    /** Runs [input] (one array per channel) through a limiter with [gainDb] in 1024-frame buffers and drains it. */
    private fun limit(input: Array<FloatArray>, gainDb: Float): Array<FloatArray> {
        val channels = input.size
        val limiter = TruePeakLimiter().apply { this.gainDb = gainDb }
        limiter.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
        limiter.flush()
        val out = Array(channels) { ArrayList<Float>() }
        fun drain() {
            val output = limiter.output
            while (output.hasRemaining()) for (c in 0 until channels) out[c] += output.getShort() / 32_768f
        }
        val frames = input[0].size
        var position = 0
        while (position < frames) {
            val count = minOf(1_024, frames - position)
            val buffer = ByteBuffer.allocateDirect(count * 2 * channels).order(ByteOrder.nativeOrder())
            for (i in 0 until count) {
                for (c in 0 until channels) buffer.putShort((input[c][position + i] * 32_767).toInt().toShort())
            }
            buffer.flip()
            limiter.queueInput(buffer)
            drain()
            position += count
        }
        limiter.queueEndOfStream()
        drain()
        return Array(channels) { out[it].toFloatArray() }
    }

    /**
     * 5.1 or 7.1 from the stereo material, in Android's channel order: L, R, centre (their mix), LFE (the low end),
     * surrounds a little quieter; 7.1 adds side channels.
     */
    private fun surround(stereo: Array<FloatArray>, channels: Int): Array<FloatArray> {
        val (left, right) = stereo
        val frames = left.size
        var low = 0f
        val lfe = FloatArray(frames) { n -> low += ((left[n] + right[n]) * 0.5f - low) * 0.01f; low * 2f }
        return Array(channels) { c ->
            FloatArray(frames) { n ->
                when (c) {
                    0 -> left[n]
                    1 -> right[n]
                    2 -> (left[n] + right[n]) * 0.5f
                    3 -> lfe[n]
                    4, 6 -> left[n] * 0.6f
                    else -> right[n] * 0.6f
                }
            }
        }
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
        val inputLufs = measure(input)
        val info = ReplayGainInfo((LoudnessMeter.REFERENCE_LUFS - inputLufs).toFloat())
        for (target in LoudnessTarget.entries) {
            val output = limit(input, LoudnessTargets.gainDb(info, target, 0f))
            val lufs = measure(output)
            val peak = output.maxOf(::truePeakDb)
            println("loudness target %s: in %.2f LUFS, out %.2f LUFS, %.2f dBTP".format(target, inputLufs, lufs, peak))
            assertEquals(target.name, target.lufs.toDouble(), lufs, 0.5)
            assertTrue("${target.name}: %.2f dBTP".format(peak), peak <= -1.0)
        }
    }

    @Test
    fun surroundTargetsAreHeldAndEveryChannelStaysBelowMinus1() {
        for (channels in listOf(6, 8)) {
            val input = surround(material(), channels)
            val inputLufs = measure(input)
            val info = ReplayGainInfo((LoudnessMeter.REFERENCE_LUFS - inputLufs).toFloat())
            for (target in LoudnessTarget.entries) {
                val output = limit(input, LoudnessTargets.gainDb(info, target, 0f))
                val lufs = measure(output)
                val peak = output.maxOf(::truePeakDb)
                println("%d channels, target %s: in %.2f LUFS, out %.2f LUFS, %.2f dBTP".format(
                    channels, target, inputLufs, lufs, peak
                ))
                assertEquals("$channels ch ${target.name}", target.lufs.toDouble(), lufs, 0.5)
                assertTrue("$channels ch ${target.name}: %.2f dBTP".format(peak), peak <= -1.0)
            }
        }
    }

    @Test
    fun surroundIntersamplePeakInOneChannelPullsAllDownTogether() {
        // fs/4 at 45 degrees, full scale, only in the centre of a 5.1 frame; the other channels play a quiet tone.
        val frames = rate / 2
        val input = Array(6) { c ->
            FloatArray(frames) { n ->
                if (c == 2) (sin(PI / 2 * n + PI / 4) / sin(PI / 4) * 0.999).toFloat()
                else (0.1 * sin(2 * PI * 440 * n / rate)).toFloat()
            }
        }
        val output = limit(input, 0f)
        // With no gain and not armed nothing may change: bit for bit.
        for (c in 0 until 6) {
            assertTrue(output[c].indices.all { abs(output[c][it] - (input[c][it] * 32_767).toInt() / 32_768f) < 1e-6f })
        }
        // Armed, the centre's peak between the samples is caught and the quiet channels go down by the same gain.
        val armedOutput = limitArmed(input)
        assertTrue(truePeakDb(armedOutput[2]) <= -1.0)
        val quietIn = input[0].copyOfRange(rate / 10, frames - 2_000).maxOf { abs(it) }
        val quietOut = armedOutput[0].copyOfRange(rate / 10, frames - 2_000).maxOf { abs(it) }
        val centreReduction = 20 * log10(armedOutput[2].maxOf { abs(it) } / 0.999)
        assertEquals(centreReduction, 20 * log10((quietOut / quietIn).toDouble()), 0.3)
    }

    private fun limitArmed(input: Array<FloatArray>): Array<FloatArray> {
        val channels = input.size
        val limiter = TruePeakLimiter().apply { armed = true }
        limiter.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
        limiter.flush()
        val out = Array(channels) { ArrayList<Float>() }
        val buffer = ByteBuffer.allocateDirect(input[0].size * 2 * channels).order(ByteOrder.nativeOrder())
        for (i in input[0].indices) for (c in 0 until channels) buffer.putShort((input[c][i] * 32_767).toInt().toShort())
        buffer.flip()
        limiter.queueInput(buffer)
        fun drain() {
            val output = limiter.output
            while (output.hasRemaining()) for (c in 0 until channels) out[c] += output.getShort() / 32_768f
        }
        drain()
        limiter.queueEndOfStream()
        drain()
        return Array(channels) { out[it].toFloatArray() }
    }
}
