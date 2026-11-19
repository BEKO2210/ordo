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
        assertEquals("48 kHz", VideoInfo.sampleRate(48_000, Locale.GERMAN))
        assertEquals("44,1 kHz", VideoInfo.sampleRate(44_100, Locale.GERMAN))
        assertNull(VideoInfo.sampleRate(-1))
    }

    @Test
    fun tracksAreDescribedAndAnUndecodableOneIsMarked() {
        val video = androidx.media3.common.Format.Builder().setSampleMimeType("video/avc")
            .setWidth(1280).setHeight(720).setFrameRate(25f).build()
        val ac3 = androidx.media3.common.Format.Builder().setSampleMimeType("audio/ac3")
            .setChannelCount(6).setSampleRate(48_000).build()
        // Tracks/TrackGroup need android.text.TextUtils; the formats themselves do not.
        val tracks = listOf(
            Triple(androidx.media3.common.C.TRACK_TYPE_VIDEO, video, true),
            Triple(androidx.media3.common.C.TRACK_TYPE_AUDIO, ac3, false)
        )
        val info = VideoInfo.fromFormats("clip", "clip.mkv", 60_000, tracks, "not playable")
        assertEquals(
            "1280 × 720 · 25 fps", VideoInfo.resolution(info.width, info.height, info.frameRate, Locale.GERMAN)
        )
        assertEquals("H.264" to false, info.videoCodec)
        assertEquals(
            listOf(
                // No language here: Format normalises it through android.text.TextUtils, which a JVM test lacks.
                listOf("Dolby Digital (AC3)", "5.1", "48 kHz", "not playable")
            ),
            info.audio
        )
    }

    @Test
    fun aCamerasRecordingDateIsRead() {
        // 16 Nov 2026 15:43:20 UTC.
        assertEquals(1_794_843_800_000L, VideoInfo.parseMetadataDate("20261116T154320.000Z"))
        assertNull(VideoInfo.parseMetadataDate("garbage"))
    }
}
