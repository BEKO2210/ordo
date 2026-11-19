/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.background

import me.zhanghai.android.files.player.AUDIO_QUEUE_SIZE_MAX
import me.zhanghai.android.files.viewer.video.VideoRepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSelectionTest {
    @Test
    fun normalizeLanguageMapsTagsToIso6391() {
        assertEquals("de", VideoSelection.normalizeLanguage("de-DE"))
        assertEquals("en", VideoSelection.normalizeLanguage("en_US"))
        assertEquals("de", VideoSelection.normalizeLanguage("deu"))
        assertEquals("de", VideoSelection.normalizeLanguage("ger"))
        assertEquals("en", VideoSelection.normalizeLanguage("eng"))
        assertEquals("fr", VideoSelection.normalizeLanguage("fra"))
        assertEquals("fr", VideoSelection.normalizeLanguage("fre"))
        assertEquals("es", VideoSelection.normalizeLanguage("spa"))
        assertEquals("it", VideoSelection.normalizeLanguage("ita"))
        assertEquals("tr", VideoSelection.normalizeLanguage("tur"))
        assertEquals("ru", VideoSelection.normalizeLanguage("rus"))
        assertEquals("ja", VideoSelection.normalizeLanguage("jpn"))
        assertEquals("pt", VideoSelection.normalizeLanguage("por"))
        assertEquals("pl", VideoSelection.normalizeLanguage("pol"))
        assertEquals("nl", VideoSelection.normalizeLanguage("nld"))
        assertEquals("nl", VideoSelection.normalizeLanguage("dut"))
        assertEquals("xx", VideoSelection.normalizeLanguage("xx"))
        assertEquals("xyz", VideoSelection.normalizeLanguage("xyz"))
        assertNull(VideoSelection.normalizeLanguage("und"))
        assertNull(VideoSelection.normalizeLanguage(""))
        assertNull(VideoSelection.normalizeLanguage("   "))
        assertNull(VideoSelection.normalizeLanguage(null))
    }

    @Test
    fun matchTrackSkipsUnsupportedPrefersSelected() {
        val tracks = listOf(
            VideoTrackInfo(0, 0, "eng", supported = false, selected = true),
            VideoTrackInfo(0, 1, "de-DE", supported = true, selected = false),
            VideoTrackInfo(0, 2, "deu", supported = true, selected = true),
            VideoTrackInfo(1, 0, "fra", supported = true, selected = false)
        )
        assertEquals(tracks[2], VideoSelection.matchTrack(tracks, "de"))
        assertEquals(tracks[1], VideoSelection.matchTrack(tracks.filter { it.trackIndex != 2 }, "ger"))
        assertNull(VideoSelection.matchTrack(tracks, "en"))
        assertNull(VideoSelection.matchTrack(tracks, "ja"))
        assertNull(VideoSelection.matchTrack(tracks, "und"))
    }

    @Test
    fun folderQueueSortsByNameAndFindsStart() {
        val paths = listOf("/v/c.mp4", "/v/a.mp4", "/v/b.mp4")
        val (queue, start) = VideoSelection.folderQueue(paths, "/v/b.mp4", naturalOrder())
        assertEquals(listOf("/v/a.mp4", "/v/b.mp4", "/v/c.mp4"), queue)
        assertEquals(1, start)
    }

    @Test
    fun folderQueueMissingStartIsZeroAndEmptyStaysEmpty() {
        val paths = listOf("/v/b.mp4", "/v/a.mp4")
        val (queue, start) = VideoSelection.folderQueue(paths, "/v/missing.mp4", naturalOrder())
        assertEquals(listOf("/v/a.mp4", "/v/b.mp4"), queue)
        assertEquals(0, start)
        assertEquals(emptyList<String>() to 0, VideoSelection.folderQueue(emptyList(), null, naturalOrder()))
    }

    @Test
    fun folderQueueTiesBreakByFullPath() {
        val paths = listOf("/z/a.mp4", "/a/a.mp4")
        val (queue, _) = VideoSelection.folderQueue(paths, null, naturalOrder())
        assertEquals(listOf("/a/a.mp4", "/z/a.mp4"), queue)
    }

    @Test
    fun folderQueueTruncatesAroundStart() {
        val paths = (0 until AUDIO_QUEUE_SIZE_MAX + 50).map { i ->
            "/v/${i.toString().padStart(4, '0')}.mp4"
        }
        val startPath = paths[AUDIO_QUEUE_SIZE_MAX / 2 + 20]
        val (queue, start) = VideoSelection.folderQueue(paths, startPath, naturalOrder())
        assertEquals(AUDIO_QUEUE_SIZE_MAX, queue.size)
        assertEquals(startPath, queue[start])
        assertTrue(start in 0 until queue.size)
    }

    @Test
    fun nextRepeatCycles() {
        assertEquals(VideoRepeatMode.ONE, VideoSelection.nextRepeat(VideoRepeatMode.STOP))
        assertEquals(VideoRepeatMode.NEXT, VideoSelection.nextRepeat(VideoRepeatMode.ONE))
        assertEquals(VideoRepeatMode.STOP, VideoSelection.nextRepeat(VideoRepeatMode.NEXT))
    }

    @Test
    fun clampSpeedBoundsAndNan() {
        assertEquals(0.25f, VideoSelection.clampSpeed(0.1f))
        assertEquals(4f, VideoSelection.clampSpeed(8f))
        assertEquals(1.5f, VideoSelection.clampSpeed(1.5f))
        assertEquals(1f, VideoSelection.clampSpeed(Float.NaN))
    }
}
