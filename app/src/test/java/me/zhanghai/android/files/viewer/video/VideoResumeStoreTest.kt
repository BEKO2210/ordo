/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoResumeStoreTest {
    @Test
    fun keyChangesWithSizeAndModificationTime() {
        val key = VideoResumeStore.key("file:///Movies/a.mp4", 1_000L, 5L)
        assertEquals(key, VideoResumeStore.key("file:///Movies/a.mp4", 1_000L, 5L))
        assertNotEquals(key, VideoResumeStore.key("file:///Movies/a.mp4", 1_001L, 5L))
        assertNotEquals(key, VideoResumeStore.key("file:///Movies/a.mp4", 1_000L, 6L))
        assertNotEquals(key, VideoResumeStore.key("file:///Movies/b.mp4", 1_000L, 5L))
    }

    @Test
    fun aKeyGivesBackItsPathAndSize() {
        val key = VideoResumeStore.key("file:///Movies/a%7Cb.mp4", 1_000L, 5L)
        assertEquals("file:///Movies/a%7Cb.mp4" to 1_000L, VideoResumeStore.parseKey(key))
        assertNull(VideoResumeStore.parseKey("garbage"))
        assertNull(VideoResumeStore.parseKey("file:///a.mp4|x|5"))
    }

    @Test
    fun onlyTheMiddleOfAFilmIsKept() {
        val duration = 200_000L
        assertFalse(VideoResumeStore.isWorthKeeping(9_999L, duration))
        assertTrue(VideoResumeStore.isWorthKeeping(10_000L, duration))
        assertTrue(VideoResumeStore.isWorthKeeping(190_000L, duration))
        assertFalse(VideoResumeStore.isWorthKeeping(190_001L, duration))
        assertFalse(VideoResumeStore.isWorthKeeping(41_000L, 0L))
        // 95 % of a 3-minute film still leaves 9 s: that is the end too.
        assertTrue(VideoResumeStore.isWorthKeeping(169_000L, 180_000L))
        assertFalse(VideoResumeStore.isWorthKeeping(171_000L, 180_000L))
    }

    @Test
    fun theOldestEntriesGoBeyondTheCap() {
        val saved = (1..302).associate { "k$it" to it.toLong() }
        assertEquals(listOf("k1", "k2", "k3"), VideoResumeStore.keysToEvict(saved, VideoResumeStore.MAX_ENTRIES - 1))
        assertEquals(emptyList<String>(), VideoResumeStore.keysToEvict(mapOf("a" to 1L), VideoResumeStore.MAX_ENTRIES - 1))
    }

    @Test
    fun entriesRoundTripAndGarbageIsIgnored() {
        assertEquals(41_000L to 7L, VideoResumeStore.decode(VideoResumeStore.encode(41_000L, 7L)))
        assertNull(VideoResumeStore.decode(null))
        assertNull(VideoResumeStore.decode("41000"))
        assertNull(VideoResumeStore.decode("x:1"))
        assertNull(VideoResumeStore.decode("-5:1"))
    }
}
