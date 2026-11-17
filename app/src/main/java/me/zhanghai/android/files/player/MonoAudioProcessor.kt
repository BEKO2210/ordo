/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Keeps stereo output but replaces both channels with a constant-gain mono mix when requested. */
class MonoAudioProcessor : BaseAudioProcessor() {
    @Volatile
    var monoEnabled: Boolean = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == 2) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // The pipeline also polls with an empty buffer; before the first data our output buffer is that same
        // EMPTY_BUFFER, and copying a buffer into itself throws, which failed every stereo song.
        if (!inputBuffer.hasRemaining()) return
        val output = replaceOutputBuffer(inputBuffer.remaining())
        if (!monoEnabled) {
            output.put(inputBuffer)
        } else {
            while (inputBuffer.remaining() >= BYTES_PER_STEREO_FRAME) {
                val left = inputBuffer.getShort().toInt()
                val right = inputBuffer.getShort().toInt()
                // Half of each channel is the documented constant-gain stereo-to-mono matrix.
                // https://developer.android.com/reference/androidx/media3/common/audio/ChannelMixingMatrix
                val mono = mixStereoSample(left, right)
                output.putShort(mono)
                output.putShort(mono)
            }
        }
        output.flip()
    }

    companion object {
        private const val BYTES_PER_STEREO_FRAME = 4

        internal fun mixStereoSample(left: Int, right: Int): Short =
            ((left + right) * 0.5f).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}

class AudioRenderersFactory(
    context: Context,
    private val monoAudioProcessor: MonoAudioProcessor
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean
    ): AudioSink = DefaultAudioSink.Builder(context)
        // PCM processors are the Media3-supported insertion point before AudioTrack playback.
        // https://developer.android.com/reference/androidx/media3/exoplayer/audio/DefaultAudioSink.Builder
        .setAudioProcessors(arrayOf<AudioProcessor>(monoAudioProcessor))
        .build()
}
