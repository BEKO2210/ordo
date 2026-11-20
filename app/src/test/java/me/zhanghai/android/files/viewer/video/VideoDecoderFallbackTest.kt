/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

import android.os.Handler
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.ffmpeg.FfmpegVideoDecoder
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDecoderFallbackTest {
    private val asked = mutableListOf<String>()
    private val platform = MediaCodecSelector { mimeType, _, _ ->
        asked += mimeType
        emptyList<MediaCodecInfo>()
    }

    @Test
    fun deviceDecodersComeFirst() {
        val fallback = VideoDecoderFallback(platform) { true }
        fallback.getDecoderInfos(MimeTypes.VIDEO_H265, false, false)
        assertEquals(listOf(MimeTypes.VIDEO_H265), asked)
    }

    @Test
    fun afterAFailedDeviceDecoderFfmpegTakesThatFormatOnce() {
        val fallback = VideoDecoderFallback(platform) { it == MimeTypes.VIDEO_H265 }
        assertTrue(fallback.blockAfterFailure(MimeTypes.VIDEO_H265, decoderFailed = true))
        fallback.getDecoderInfos(MimeTypes.VIDEO_H265, false, false)
        fallback.getDecoderInfos(MimeTypes.VIDEO_H264, false, false)
        // HEVC no longer reaches the device's list (so MediaCodecVideoRenderer says "unsupported"), H.264 still does.
        assertEquals(listOf(MimeTypes.VIDEO_H264), asked)
        // FFmpeg failing as well is not retried in a loop.
        assertFalse(fallback.blockAfterFailure(MimeTypes.VIDEO_H265, decoderFailed = true))
    }

    @Test
    fun noRetryWithoutReason() {
        val fallback = VideoDecoderFallback(platform) { it == MimeTypes.VIDEO_H265 }
        assertFalse(fallback.blockAfterFailure(MimeTypes.VIDEO_H265, decoderFailed = false))
        assertFalse(fallback.blockAfterFailure(null, decoderFailed = true))
        // AV1 and VP9 are not in Ordo's FFmpeg build: the error stays.
        assertFalse(fallback.blockAfterFailure(MimeTypes.VIDEO_AV1, decoderFailed = true))
    }

    @Test
    fun ffmpegBuildCoversTheCommonFormats() {
        assertEquals("hevc", FfmpegVideoDecoder.getCodecName(MimeTypes.VIDEO_H265))
        assertEquals("h264", FfmpegVideoDecoder.getCodecName(MimeTypes.VIDEO_H264))
        assertEquals("mpeg2video", FfmpegVideoDecoder.getCodecName(MimeTypes.VIDEO_MPEG2))
        // Xvid/DivX in AVI (video/mp4v-es) and DivX in Matroska.
        assertEquals("mpeg4", FfmpegVideoDecoder.getCodecName(MimeTypes.VIDEO_MP4V))
        assertEquals("mpeg4", FfmpegVideoDecoder.getCodecName(MimeTypes.VIDEO_DIVX))
        assertEquals("msmpeg4", FfmpegVideoDecoder.getCodecName(MimeTypes.VIDEO_MP43))
        assertEquals("vc1", FfmpegVideoDecoder.getCodecName(MimeTypes.VIDEO_VC1))
        assertNull(FfmpegVideoDecoder.getCodecName(MimeTypes.VIDEO_AV1))
        assertNull(FfmpegVideoDecoder.getCodecName(MimeTypes.AUDIO_AC3))
        assertNull(FfmpegVideoDecoder.getCodecName(null))
    }

    // EXTENSION_RENDERER_MODE_ON makes DefaultRenderersFactory look the renderer up by this name and constructor and
    // silently skip it when either is missing.
    @Test
    fun ffmpegVideoRendererHasTheConstructorMedia3Reflects() {
        Class.forName("androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer").getConstructor(
            Long::class.javaPrimitiveType, Handler::class.java, VideoRendererEventListener::class.java,
            Int::class.javaPrimitiveType
        )
    }
}
