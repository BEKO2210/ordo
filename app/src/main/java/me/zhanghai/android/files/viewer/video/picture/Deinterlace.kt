/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil

/**
 * Whether a video is interlaced (1080i broadcast, DVD and MPEG-2 TS rips), from what Media3's Format carries: H.264's
 * SPS (frame_mbs_only_flag 0 = fields may come) and MPEG-2's sequence extension (progressive_sequence 0). Other codecs
 * say nothing here (null); the "Bild" sheet's switch decides then. An H.264 SPS only allows fields: at film rate
 * (24p and below, Blu-ray encoders set the flag too) it counts as unknown. Unit-tested in DeinterlaceTest.
 */
@OptIn(UnstableApi::class)
object Deinterlace {
    /** true interlaced, false progressive, null unknown. */
    fun detect(mimeType: String?, initializationData: List<ByteArray>, frameRate: Float = -1f): Boolean? =
        when (mimeType) {
            "video/avc" -> initializationData.firstNotNullOfOrNull(::avcInterlaced)
                ?.takeIf { !it || frameRate <= 0f || frameRate > FILM_MAX_FPS }
            "video/mpeg2" -> initializationData.firstNotNullOfOrNull(::mpeg2Interlaced)
            else -> null
        }

    /** The viewer's choice wins; without one only a detected interlaced video is deinterlaced. */
    fun active(choice: Boolean?, detected: Boolean?): Boolean = choice ?: (detected == true)

    private fun avcInterlaced(data: ByteArray): Boolean? {
        val start = nalStarts(data).firstOrNull { it < data.size && data[it].toInt() and 0x1F == NAL_SPS } ?: return null
        val end = nalStarts(data).firstOrNull { it > start }?.let { it - 3 } ?: data.size
        return runCatching { !NalUnitUtil.parseSpsNalUnit(data, start, end).frameMbsOnlyFlag }.getOrNull()
    }

    /** Sequence extension: 00 00 01 B5, 4 bits id (1), 8 bits profile and level, then progressive_sequence. */
    private fun mpeg2Interlaced(data: ByteArray): Boolean? {
        for (i in 0..data.size - 6) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1 &&
                data[i + 3].toInt() and 0xFF == 0xB5 && data[i + 4].toInt() and 0xF0 == 0x10
            ) {
                return data[i + 5].toInt() shr 3 and 1 == 0
            }
        }
        return null
    }

    /** Where each NAL unit starts after a 00 00 01 start code; a lone SPS without one starts at 0. */
    private fun nalStarts(data: ByteArray): List<Int> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                starts += i + 3
                i += 3
            } else {
                i++
            }
        }
        return starts.ifEmpty { listOf(0) }
    }

    private const val NAL_SPS = 7
    private const val FILM_MAX_FPS = 24.5f
}
