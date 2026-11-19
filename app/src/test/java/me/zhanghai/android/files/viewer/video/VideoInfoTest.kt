/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoInfoTest {
    @Test
    fun valuesInPlainWords() {
        assertEquals("5.1", VideoInfo.channels(6))
        assertEquals("Stereo", VideoInfo.channels(2))
        assertNull(VideoInfo.channels(-1))
        assertEquals("1920 × 1080 · 30 fps", VideoInfo.resolution(1920, 1080, 30f, Locale.GERMAN))
        assertEquals("3840 × 2160 · 29,97 fps", VideoInfo.resolution(3840, 2160, 29.97f, Locale.GERMAN))
        assertEquals("640 × 360", VideoInfo.resolution(640, 360, 0f))
        assertEquals("8,2 Mbit/s", VideoInfo.bitrate(8_200_000, Locale.GERMAN))
        assertEquals("320 kbit/s", VideoInfo.bitrate(320_000, Locale.GERMAN))
        assertNull(VideoInfo.bitrate(0))
    }

    @Test
    fun aCamerasRecordingDateIsRead() {
        // 16 Nov 2026 15:43:20 UTC.
        assertEquals(1_794_843_800_000L, VideoInfo.parseMetadataDate("20261116T154320.000Z"))
        assertNull(VideoInfo.parseMetadataDate("garbage"))
    }
}
