/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

/**
 * What "Signal path" needs to know to say whether picture and sound reach the screen and the ears untouched; the
 * rule itself has no Android in it (unit-tested in PicturePolicyTest). "Untouched" is only said when nothing on the
 * way changes the signal: neither Ordo nor Android.
 */
object SignalPath {
    enum class VideoChange { ENHANCED, TONE_MAPPED }

    enum class AudioChange { STAGES, SPEED, RESAMPLED, DOWNMIXED, SPATIALIZED }

    /** [hdrShownAsHdr]: the screen can show this HDR type itself; otherwise Android maps it down to SDR. */
    fun videoChanges(enhanced: Boolean, isHdr: Boolean, hdrShownAsHdr: Boolean): List<VideoChange> = buildList {
        if (enhanced) add(VideoChange.ENHANCED)
        if (isHdr && !hdrShownAsHdr) add(VideoChange.TONE_MAPPED)
    }

    /**
     * [passthrough]: the coded stream goes to the device as it is (AC3 over HDMI), nothing is decoded or mixed here.
     * [trackRate] is the rate the player hands to Android, [mixerRate] Android's own output rate (0 if unknown, then
     * no resampling is claimed). [deviceChannels] is the most the output takes (0: unknown,
     * then no downmix is claimed).
     */
    fun audioChanges(
        passthrough: Boolean,
        stages: List<String>,
        speed: Float,
        trackRate: Int,
        mixerRate: Int,
        channels: Int,
        deviceChannels: Int,
        spatialized: Boolean
    ): List<AudioChange> = buildList {
        if (passthrough) return@buildList
        if (stages.isNotEmpty()) add(AudioChange.STAGES)
        if (speed != 1f) add(AudioChange.SPEED)
        if (trackRate > 0 && mixerRate > 0 && trackRate != mixerRate) add(AudioChange.RESAMPLED)
        if (spatialized) {
            add(AudioChange.SPATIALIZED)
        } else if (deviceChannels in 1 until channels) {
            add(AudioChange.DOWNMIXED)
        }
    }

    /** "c2.android.avc.decoder", "OMX.google.h264.decoder": Android's software decoders, by name (pre-API-29 rule). */
    fun isSoftwareDecoderName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("omx.google.") || lower.startsWith("c2.android.") || lower.startsWith("omx.ffmpeg.") ||
            lower.startsWith("c2.ffmpeg.") || lower.contains(".sw.") || lower.endsWith(".sw")
    }
}
