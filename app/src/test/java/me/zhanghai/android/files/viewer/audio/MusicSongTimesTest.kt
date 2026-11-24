/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicSongTimesTest {
    private val song = MusicSong(
        path = "/a.mp3", title = "a", artist = null, album = null, albumId = 1, durationMs = 1,
        track = 0, mimeType = "audio/mpeg", dateModifiedMs = 5_000
    )

    @Test
    fun fileTimeKeepsMilliseconds() {
        assertEquals(5_123L, song.withFileTimes(5_123).dateModifiedMs)
    }

    @Test
    fun missingFileTimeKeepsMediaStoreTime() {
        assertEquals(5_000L, song.withFileTimes(0).dateModifiedMs)
    }
}
