/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.sound

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.Util
import me.zhanghai.android.files.player.KlangAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * 3 to 8 channels (16 bit) down to stereo with Ordo's own matrix ([VideoSoundMath.downmixMatrix]): the centre, where
 * films put the dialogue, keeps −3 dB or more instead of whatever the device's mixer does. Stereo and mono pass by.
 * A change of [centreGain] glides across one buffer; a peak limiter (same ceiling as the Klang chain) keeps the sum
 * from clipping.
 */
class DownmixProcessor : BaseAudioProcessor() {
    @Volatile
    var centreGain: Float = VideoSoundMath.centreGain(VideoSoundMath.DIALOG_OFF)

    /**
     * Channel counts the system's spatializer renders for the current output ([VideoSpatial]): those pass untouched.
     * Read when a format is configured (a new film or track), not in the middle of one.
     */
    @Volatile
    var passThroughChannels: Set<Int> = emptySet()

    private var matrix: Array<FloatArray> = emptyArray()
    private var target: Array<FloatArray> = emptyArray()
    private var appliedCentre = Float.NaN
    private var limiterGain = 1f
    private var release = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val channels = inputAudioFormat.channelCount
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            VideoSoundMath.downmixMatrix(channels, centreGain) == null
        ) return AudioProcessor.AudioFormat.NOT_SET
        // Android's Log is not available in JVM tests; the engine logs the decision when the track arrives.
        if (channels in passThroughChannels) return AudioProcessor.AudioFormat.NOT_SET
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun onFlush() {
        appliedCentre = centreGain
        matrix = VideoSoundMath.downmixMatrix(inputAudioFormat.channelCount, appliedCentre) ?: emptyArray()
        target = matrix
        limiterGain = 1f
        release = (1.0 - exp(-1.0 / (inputAudioFormat.sampleRate * RELEASE_SECONDS))).toFloat()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val channels = inputAudioFormat.channelCount
        val frames = inputBuffer.remaining() / (channels * 2)
        val centre = centreGain
        val from = matrix
        if (centre != appliedCentre) {
            appliedCentre = centre
            target = VideoSoundMath.downmixMatrix(channels, centre) ?: from
        }
        val gliding = target !== from
        val output = replaceOutputBuffer(frames * 4)
        for (frame in 0 until frames) {
            val t = if (gliding) (frame + 1f) / frames else 1f
            var left = 0f
            var right = 0f
            for (channel in 0 until channels) {
                val sample = inputBuffer.getShort() / PCM_SCALE
                var l = from[0][channel]
                var r = from[1][channel]
                if (gliding) {
                    l += (target[0][channel] - l) * t
                    r += (target[1][channel] - r) * t
                }
                left += l * sample
                right += r * sample
            }
            var gain = limiterGain
            if (gain < 1f) {
                gain += (1f - gain) * release
                if (gain > 0.99999f) gain = 1f
            }
            val level = maxOf(abs(left), abs(right))
            if (level * gain > KlangAudioProcessor.CEILING) gain = KlangAudioProcessor.CEILING / level
            limiterGain = gain
            output.putShort(toShort(left * gain))
            output.putShort(toShort(right * gain))
        }
        matrix = target
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    private fun toShort(value: Float): Short =
        (value * PCM_SCALE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private companion object {
        const val PCM_SCALE = 32_768f
        const val RELEASE_SECONDS = 0.08
    }
}

/**
 * A/V sync. ExoPlayer's clock is the audio clock: the frames the AudioTrack has played. Inserting N frames of silence
 * after a flush makes the clock run N ahead of the sound (sound later than the picture); dropping N frames makes it
 * run N behind (sound earlier). So a change applies with the next flush; the engine seeks to the current position to
 * get one. Works on any linear PCM that reaches the processors.
 */
class AvDelayProcessor : BaseAudioProcessor() {
    /** Positive: the sound comes later; negative: earlier. */
    @Volatile
    var delayMs: Int = 0

    private var pendingFrames = 0L
    private var frameSize = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!Util.isEncodingLinearPcm(inputAudioFormat.encoding)) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush() {
        frameSize = Util.getPcmFrameSize(inputAudioFormat.encoding, inputAudioFormat.channelCount)
        pendingFrames = VideoSoundMath.delayFrames(delayMs, inputAudioFormat.sampleRate)
    }

    override fun onReset() {
        pendingFrames = 0L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        if (pendingFrames < 0) {
            val skip = minOf(-pendingFrames, (inputBuffer.remaining() / frameSize).toLong())
            inputBuffer.position(inputBuffer.position() + (skip * frameSize).toInt())
            pendingFrames += skip
            if (!inputBuffer.hasRemaining()) return
        }
        val silence = if (pendingFrames > 0) (pendingFrames * frameSize).toInt() else 0
        pendingFrames = minOf(pendingFrames, 0L)
        val output = replaceOutputBuffer(silence + inputBuffer.remaining())
        // Zero is silence for 16-bit and float PCM, the only encodings DefaultAudioSink hands its processors.
        repeat(silence) { output.put(0) }
        output.put(inputBuffer)
        output.flip()
    }
}
