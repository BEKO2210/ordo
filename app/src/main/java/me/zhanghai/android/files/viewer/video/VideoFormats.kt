/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

/**
 * What happens when a video ends, chosen with the repeat button and kept for every video. [STOP] is the default:
 * the picture stays on its last frame with "Watch again" and "Next video" (the owner's choice, 19.11.2026).
 */
enum class VideoRepeatMode {
    STOP, ONE, NEXT;

    /** The button's cycle: stop → repeat this video → play the next automatically → stop. */
    fun next(): VideoRepeatMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun decode(value: String?): VideoRepeatMode = entries.firstOrNull { it.name == value } ?: STOP
    }
}

/** A codec's name as people know it, for "this device can't play …" messages; null when the type is unknown. */
internal fun audioCodecName(mimeType: String?): String? = when (mimeType) {
    "audio/ac3" -> "Dolby Digital (AC3)"
    "audio/eac3", "audio/eac3-joc" -> "Dolby Digital Plus (E-AC3)"
    "audio/ac4" -> "Dolby AC-4"
    "audio/true-hd" -> "Dolby TrueHD"
    "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.hd;profile=lbr", "audio/vnd.dts.uhd;profile=p2" -> "DTS"
    "audio/mpeg-L2" -> "MP2"
    "audio/mpeg" -> "MP3"
    "audio/mp4a-latm", "audio/aac", "audio/x-aac", "audio/aac-adts" -> "AAC"
    "audio/opus" -> "Opus"
    "audio/vorbis" -> "Vorbis"
    "audio/flac" -> "FLAC"
    "audio/raw" -> "PCM"
    "audio/3gpp", "audio/amr-wb" -> "AMR"
    "audio/alac" -> "ALAC"
    else -> null
}

/**
 * The video codec's name and whether it is the 10-bit kind, from the sample MIME type, the RFC 6381 codecs string
 * ("hvc1.2…" is HEVC Main 10, "vp09.02…" VP9 profile 2, "av01.0.08M.10" AV1 10-bit) or the luma bit depth.
 */
internal fun videoCodecName(mimeType: String?, codecs: String?, lumaBitDepth: Int): Pair<String, Boolean>? {
    val name = when (mimeType) {
        "video/hevc" -> "HEVC"
        "video/avc" -> "H.264"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/av01" -> "AV1"
        "video/dolby-vision" -> "Dolby Vision"
        "video/mp4v-es" -> "MPEG-4"
        "video/mpeg2" -> "MPEG-2"
        else -> return null
    }
    val c = codecs.orEmpty()
    val tenBit = lumaBitDepth == 10 || c.startsWith("hvc1.2") || c.startsWith("hev1.2") || c.startsWith("vp09.02") ||
        (c.startsWith("av01.") && c.split('.').getOrNull(3) == "10")
    return name to tenBit
}
