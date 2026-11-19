/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import android.os.Handler
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Test

class FfmpegRendererTest {
    // EXTENSION_RENDERER_MODE_ON makes DefaultRenderersFactory look the renderer up by this name and constructor and
    // silently skip it when either is missing, e.g. after a Media3 update without rerunning utils/ffmpeg-audio/build.sh.
    @Test
    fun ffmpegAudioRendererHasTheConstructorMedia3Reflects() {
        Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")
            .getConstructor(Handler::class.java, AudioRendererEventListener::class.java, AudioSink::class.java)
    }
}
