/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegVideoDecoder
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.util.concurrent.ConcurrentHashMap

/**
 * Picks the video decoder: the device's first, FFmpeg (ExperimentalFfmpegVideoRenderer, EXTENSION_RENDERER_MODE_ON)
 * when no device decoder takes the format. Some chips claim a format and then fail to start (Unisoc W90 with HEVC
 * 10-bit, 19.11.2026); after such a failure [blockAfterFailure] hides the device's decoders for that MIME type from the
 * player, so the next prepare() selects FFmpeg. Lives as long as the player; the playback thread asks, the main thread
 * blocks.
 */
@UnstableApi
class VideoDecoderFallback(
    private val platform: MediaCodecSelector = MediaCodecSelector.DEFAULT,
    private val ffmpegDecodes: (String) -> Boolean = FfmpegVideoDecoder::supportsMimeType
) : MediaCodecSelector {
    private val blocked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ): List<MediaCodecInfo> =
        if (mimeType in blocked) {
            emptyList()
        } else {
            platform.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        }

    /**
     * After a video decoder failure: true when FFmpeg can take [mimeType] and it was not already tried, then the
     * caller prepares again. False keeps the error (FFmpeg itself failed, or it can't decode this either).
     */
    fun blockAfterFailure(mimeType: String?, decoderFailed: Boolean): Boolean {
        if (!decoderFailed || mimeType == null || mimeType in blocked || !ffmpegDecodes(mimeType)) return false
        blocked += mimeType
        return true
    }
}
