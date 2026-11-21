/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Interlace detection from a Format's initialization data, with SPS written by x264 (ffmpeg, 320x240). */
class DeinterlaceTest {
    // x264 with interlaced=1 (frame_mbs_only_flag 0) and without (1), NAL header 0x67 first.
    private val interlacedSps = bytes(
        103, -12, 0, 21, -111, -101, 40, 40, 33, -8, -100, 4, 64, 0, 0, 3, 0, 64, 0, 0, 12, -121, -59, 10, 101, -128
    )
    private val progressiveSps = bytes(
        103, -12, 0, 13, -111, -101, 40, 40, 63, 96, 34, 0, 0, 3, 0, 2, 0, 0, 3, 0, 100, 30, 40, 83, 44
    )
    private val pps = bytes(104, -21, -29, -53, 34, -64)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun annexB(vararg nals: ByteArray) = nals.fold(ByteArray(0)) { all, nal -> all + bytes(0, 0, 0, 1) + nal }

    @Test
    fun h264InterlacedSpsIsDetected() {
        // Media3's csd: start code + SPS, start code + PPS (as separate entries or one).
        assertEquals(true, Deinterlace.detect("video/avc", listOf(annexB(interlacedSps), annexB(pps))))
        assertEquals(true, Deinterlace.detect("video/avc", listOf(annexB(interlacedSps, pps))))
        assertEquals(true, Deinterlace.detect("video/avc", listOf(annexB(interlacedSps)), 25f))
        // At film rate the flag alone says too little (Blu-ray 1080p24 encoders set it): unknown.
        assertNull(Deinterlace.detect("video/avc", listOf(annexB(interlacedSps)), 23.976f))
        assertEquals(false, Deinterlace.detect("video/avc", listOf(annexB(progressiveSps)), 23.976f))
    }

    @Test
    fun h264ProgressiveSpsIsNot() {
        assertEquals(false, Deinterlace.detect("video/avc", listOf(annexB(progressiveSps), annexB(pps))))
        // Without a start code too.
        assertEquals(false, Deinterlace.detect("video/avc", listOf(progressiveSps)))
    }

    @Test
    fun mpeg2SequenceExtensionDecides() {
        // Sequence header start, then the extension: id 1, Main@Main (0x48), progressive_sequence in bit 3.
        val header = bytes(0, 0, 1, 0xB3, 0x2D, 0x02, 0x40, 0x33)
        val interlaced = header + bytes(0, 0, 1, 0xB5, 0x14, 0x82, 0x01, 0x00)
        val progressive = header + bytes(0, 0, 1, 0xB5, 0x14, 0x8A, 0x01, 0x00)
        assertEquals(true, Deinterlace.detect("video/mpeg2", listOf(interlaced)))
        assertEquals(false, Deinterlace.detect("video/mpeg2", listOf(progressive)))
        assertNull(Deinterlace.detect("video/mpeg2", listOf(header)))
    }

    @Test
    fun otherCodecsAndEmptyDataAreUnknown() {
        assertNull(Deinterlace.detect("video/hevc", listOf(annexB(interlacedSps))))
        assertNull(Deinterlace.detect("video/avc", emptyList()))
        assertNull(Deinterlace.detect("video/avc", listOf(annexB(pps))))
        assertNull(Deinterlace.detect(null, emptyList()))
    }

    @Test
    fun theViewersChoiceWinsOverDetection() {
        assertTrue(Deinterlace.active(null, true))
        assertFalse(Deinterlace.active(null, false))
        assertFalse(Deinterlace.active(null, null))
        assertFalse(Deinterlace.active(false, true))
        assertTrue(Deinterlace.active(true, null))
    }
}
