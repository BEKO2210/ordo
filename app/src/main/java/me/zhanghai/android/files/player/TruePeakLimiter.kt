/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The last stage of the Klang chain: keeps the output at or below −1 dBTP (EBU R 128), measured between the samples
 * too. Peaks are found with 4x oversampling (BS.1770-4 Annex 2 method; here a 64-tap Hann-windowed sinc per phase),
 * the gain is the lookahead minimum over [WINDOW] frames, averaged over [WINDOW] frames, so it ramps down before a peak
 * instead of stepping (a step makes new intersample peaks); then it recovers with an 80 ms time constant. The
 * detection target is [CEILING_DB] = −1.6 dB: the gain ramp itself adds a little between the samples (clipped full-band
 * noise lands at about −1.5 dBTP, docs/VIDEO-SOUND.md). Constant latency [LATENCY] frames (1 ms at 48 kHz).
 *
 * Before the detection it applies the loudness gain ([gainDb], EBU R 128 s2 targets, [LoudnessTargets]), gliding with a
 * 50 ms time constant, so a raised level is caught by the same limiter (R 128 s2 k).
 *
 * While nothing processes the sound ([armed] false, [armedBy] answering false, no loudness gain) no new reduction
 * starts; once the gain is back at 1 every sample passes bit for bit, so "everything off" stays bit-perfect (delayed by
 * [LATENCY] frames). 16-bit mono and stereo; other formats pass by.
 */
class TruePeakLimiter(private val armedBy: (() -> Boolean)? = null) : BaseAudioProcessor() {
    @Volatile
    var armed: Boolean = false

    /** Loudness gain in dB applied before the limiter (0 = none). */
    @Volatile
    var gainDb: Float = 0f

    /** [armed] or [armedBy] or a loudness gain, read once per buffer on the audio thread. */
    private var armedNow = false
    private var channels = 2
    private var inputGain = 1f
    private var inputGainTarget = 1f
    private var inputGlide = 0f
    private val unity = BooleanArray(RING)

    private val left = FloatArray(RING)
    private val right = FloatArray(RING)
    private val rawLeft = ShortArray(RING)
    private val rawRight = ShortArray(RING)
    private val segment = FloatArray(RING)
    private val required = FloatArray(RING)
    private val held = FloatArray(RING)
    private var count = 0L
    private var lastHeld = 1f
    private var release = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount in 1..2) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }

    override fun onFlush() {
        left.fill(0f)
        right.fill(0f)
        rawLeft.fill(0)
        rawRight.fill(0)
        segment.fill(0f)
        required.fill(1f)
        held.fill(1f)
        unity.fill(true)
        count = 0
        lastHeld = 1f
        channels = inputAudioFormat.channelCount
        release = (1.0 - exp(-1.0 / (inputAudioFormat.sampleRate * RELEASE_SECONDS))).toFloat()
        inputGlide = (1.0 - exp(-1.0 / (inputAudioFormat.sampleRate * GAIN_SECONDS))).toFloat()
        // A stream starts at its level; only changes while it plays glide.
        inputGain = Math.pow(10.0, gainDb / 20.0).toFloat()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val frameBytes = 2 * channels
        val frames = inputBuffer.remaining() / frameBytes
        inputGainTarget = Math.pow(10.0, gainDb / 20.0).toFloat()
        armedNow = armed || armedBy?.invoke() == true || inputGainTarget != 1f || inputGain != 1f
        val output = replaceOutputBuffer(frames * frameBytes)
        repeat(frames) {
            val l = inputBuffer.getShort()
            push(l, if (channels == 2) inputBuffer.getShort() else l, output)
        }
        output.flip()
    }

    // The last LATENCY frames are still in the ring: silence pushes them out.
    override fun onQueueEndOfStream() {
        val pending = minOf(count, LATENCY.toLong()).toInt()
        if (pending == 0) return
        val output = replaceOutputBuffer(pending * 2 * channels)
        repeat(LATENCY) { push(0, 0, output) }
        output.flip()
    }

    private fun push(l: Short, r: Short, output: ByteBuffer) {
        val n = count
        val i = (n and MASK).toInt()
        rawLeft[i] = l
        rawRight[i] = r
        if (inputGain != inputGainTarget) {
            inputGain += (inputGainTarget - inputGain) * inputGlide
            if (abs(inputGain - inputGainTarget) < 1e-5f) inputGain = inputGainTarget
        }
        unity[i] = inputGain == 1f
        left[i] = l / PCM_SCALE * inputGain
        right[i] = r / PCM_SCALE * inputGain
        count++
        // Segment s (between frames s and s + 1) needs frames up to s + HALF = n.
        val s = n - HALF
        if (s >= 0) {
            var peak = 0f
            // Only work while armed, and only where the window is loud enough that a point between the samples could
            // reach the ceiling at all (|interpolated| <= max|x| * sum|kernel|): quiet film sound costs a scan.
            var windowMax = 0f
            if (armedNow) {
                for (j in 0 until TAPS) {
                    val index = ((s - HALF + 1 + j) and MASK).toInt()
                    windowMax = maxOf(windowMax, abs(left[index]), abs(right[index]))
                }
            }
            if (windowMax * KERNEL_GAIN > CEILING) for (phase in 0 until 3) {
                val kernel = KERNELS[phase]
                var sumL = 0f
                var sumR = 0f
                for (j in 0 until TAPS) {
                    val index = ((s - HALF + 1 + j) and MASK).toInt()
                    sumL += left[index] * kernel[j]
                    sumR += right[index] * kernel[j]
                }
                peak = maxOf(peak, abs(sumL), abs(sumR))
            }
            val si = (s and MASK).toInt()
            segment[si] = peak
            val samplePeak = maxOf(abs(left[si]), abs(right[si]))
            val framePeak = maxOf(samplePeak, peak, segment[((s - 1) and MASK).toInt()])
            required[si] = if (armedNow && framePeak > CEILING) CEILING / framePeak else 1f
        }
        // Held gain for frame m: the minimum required over m..m + WINDOW - 1, with the slow release on top.
        val m = s - WINDOW + 1
        if (m < 0) return
        var minimum = 1f
        for (j in 0 until WINDOW) minimum = minOf(minimum, required[((m + j) and MASK).toInt()])
        var recovered = lastHeld + (1f - lastHeld) * release
        if (recovered > 0.99999f) recovered = 1f
        lastHeld = minOf(minimum, recovered)
        val mi = (m and MASK).toInt()
        held[mi] = lastHeld
        // The gain of frame m: the mean of the held gains of m - WINDOW + 1..m, each of which covers frame m.
        // The first frames after a flush have fewer held gains before them: the smallest of those, not a mean with
        // the empty ring's 1s.
        val gain = if (m < WINDOW - 1) {
            var smallest = 1f
            for (j in 0..m.toInt()) smallest = minOf(smallest, held[((m - j) and MASK).toInt()])
            smallest
        } else {
            var sum = 0f
            for (j in 0 until WINDOW) sum += held[((m - j) and MASK).toInt()]
            if (sum >= WINDOW.toFloat()) 1f else sum / WINDOW
        }
        if (gain == 1f && unity[mi]) {
            output.putShort(rawLeft[mi])
            if (channels == 2) output.putShort(rawRight[mi])
        } else {
            output.putShort(quantize(left[mi] * gain))
            if (channels == 2) output.putShort(quantize(right[mi] * gain))
        }
    }

    private fun quantize(value: Float): Short =
        (value * PCM_SCALE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    companion object {
        private const val PCM_SCALE = 32_768f
        /** 64 taps per phase, the same interpolator as the 4x reference meter in the tests. */
        private const val HALF = 32
        private const val TAPS = 2 * HALF
        internal const val WINDOW = 16
        /** Frames between input and output. */
        const val LATENCY = HALF + WINDOW - 1
        private const val RING = 128
        private const val MASK = (RING - 1).toLong()
        private const val RELEASE_SECONDS = 0.08
        private const val GAIN_SECONDS = 0.05
        internal const val CEILING_DB = -1.6f
        internal val CEILING = Math.pow(10.0, CEILING_DB / 20.0).toFloat()

        /** Hann-windowed sinc for the points 1/4, 2/4 and 3/4 between a frame and the next. */
        internal val KERNELS: Array<FloatArray> = Array(3) { phase ->
            val t = (phase + 1) / 4.0
            FloatArray(TAPS) { j ->
                val x = j - HALF + 1 - t
                val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                (sinc * (0.5 + 0.5 * cos(PI * x / HALF))).toFloat()
            }
        }

        /** The largest sum of |kernel| over the phases: a point between samples is at most this times the window peak. */
        private val KERNEL_GAIN = KERNELS.maxOf { kernel -> kernel.sumOf { abs(it).toDouble() } }.toFloat()
    }
}
