/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlin.math.roundToInt

/**
 * Keeps stereo output but replaces both channels with a constant-gain mono mix when requested. It shares the cross-fade
 * of [StereoEffectProcessor], so switching mono on or off does not crack.
 */
class MonoAudioProcessor : StereoEffectProcessor() {
    var monoEnabled: Boolean
        get() = effectEnabled
        set(value) {
            effectEnabled = value
        }

    override fun clear() {}

    override fun processFrame(left: Float, right: Float) {
        // Half of each channel is the documented constant-gain stereo-to-mono matrix.
        // https://developer.android.com/reference/androidx/media3/common/audio/ChannelMixingMatrix
        val mono = (left + right) * 0.5f
        outLeft = mono
        outRight = mono
    }

    companion object {
        internal fun mixStereoSample(left: Int, right: Int): Short =
            ((left + right) * 0.5f).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}

class AudioRenderersFactory(
    context: Context,
    private val audioProcessors: Array<AudioProcessor>
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean
    ): AudioSink = DefaultAudioSink.Builder(context)
        // PCM processors are the Media3-supported insertion point before AudioTrack playback.
        // https://developer.android.com/reference/androidx/media3/exoplayer/audio/DefaultAudioSink.Builder
        .setAudioProcessors(audioProcessors)
        .build()
}
