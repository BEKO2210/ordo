/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoFormatsTest {
    @Test
    fun repeatCyclesStopOneNextAndUnknownIsStop() {
        assertEquals(VideoRepeatMode.ONE, VideoRepeatMode.STOP.next())
        assertEquals(VideoRepeatMode.NEXT, VideoRepeatMode.ONE.next())
        assertEquals(VideoRepeatMode.STOP, VideoRepeatMode.NEXT.next())
        // Stored by name, read back the same; nothing stored or something unknown is the default.
        VideoRepeatMode.entries.forEach { assertEquals(it, VideoRepeatMode.decode(it.name)) }
        assertEquals(VideoRepeatMode.STOP, VideoRepeatMode.decode(null))
        assertEquals(VideoRepeatMode.STOP, VideoRepeatMode.decode("ALL"))
    }

    @Test
    fun codecNamesPeopleKnow() {
        assertEquals("Dolby Digital (AC3)", audioCodecName("audio/ac3"))
        assertEquals("Dolby Digital Plus (E-AC3)", audioCodecName("audio/eac3"))
        assertEquals("DTS", audioCodecName("audio/vnd.dts.hd"))
        assertEquals("Dolby TrueHD", audioCodecName("audio/true-hd"))
        assertNull(audioCodecName("audio/x-unknown"))
    }

    @Test
    fun tenBitVideoIsRecognised() {
        assertEquals("HEVC" to true, videoCodecName("video/hevc", "hvc1.2.4.L120.B0", -1))
        assertEquals("HEVC" to false, videoCodecName("video/hevc", "hvc1.1.6.L93.B0", -1))
        assertEquals("VP9" to true, videoCodecName("video/x-vnd.on2.vp9", "vp09.02.10.10", -1))
        assertEquals("AV1" to true, videoCodecName("video/av01", "av01.0.08M.10", -1))
        assertEquals("H.264" to true, videoCodecName("video/avc", null, 10))
        assertNull(videoCodecName("video/x-unknown", null, 8))
    }
}
