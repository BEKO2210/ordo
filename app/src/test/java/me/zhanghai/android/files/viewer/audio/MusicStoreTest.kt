/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MusicStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun beforeAndroid10SelectsMusicAndPodcasts() {
        val expected = "(is_music != 0 OR is_podcast != 0)"
        assertEquals(expected, musicSelection(24))
        assertEquals(expected, musicSelection(28))
    }

    @Test
    fun android10SelectsMusicAudiobooksAndPodcasts() {
        assertEquals("(is_music != 0 OR is_audiobook != 0 OR is_podcast != 0)", musicSelection(29))
    }

    @Test
    fun otherAudioCategoriesStayExcluded() {
        val selection = musicSelection(Int.MAX_VALUE)
        listOf("is_ringtone", "is_notification", "is_alarm", "is_recording").forEach {
            assertFalse(selection.contains(it))
        }
    }

    @Test
    fun additionalFoldersKeepOnlyPlayableAudioAndRemoveAllPathDuplicates() {
        val candidates = sequenceOf(
            AdditionalMusicCandidate("/recordings/note.mp3", "audio/mpeg"),
            AdditionalMusicCandidate("/recordings/readme.txt", "text/plain"),
            AdditionalMusicCandidate("/recordings/voice.opus", "audio/ogg"),
            AdditionalMusicCandidate("/recordings/voice.opus", "audio/ogg"),
            AdditionalMusicCandidate("/music/already-there.flac", "audio/flac")
        )

        val result = filterAdditionalMusic(candidates, setOf("/music/already-there.flac"))

        assertEquals(listOf("/recordings/note.mp3", "/recordings/voice.opus"), result.map { it.path })
        assertTrue(result.all { it.mimeType.startsWith("audio/") })
    }

    @Test
    fun additionalSongCacheReusesOnlyMatchingModificationTime() {
        val cache = PathModificationCache<String>(2)
        var loads = 0

        assertEquals("first", cache.getOrLoad("/music/song.mp3", 100) { loads++; "first" })
        assertEquals("first", cache.getOrLoad("/music/song.mp3", 100) { loads++; "stale" })
        assertEquals("changed", cache.getOrLoad("/music/song.mp3", 200) { loads++; "changed" })
        assertEquals(2, loads)
    }

    @Test
    fun additionalFolderScanStopsAtCancellationPoint() {
        folder.newFile("one.mp3")
        var visited = 0
        val candidates = additionalMusicCandidates(folder.root.path) {
            if (++visited == 2) throw CancellationException("cancelled")
        }

        assertThrows(CancellationException::class.java) { candidates.toList() }
        assertEquals(2, visited)
    }

    @Test
    fun additionalSongCacheRetainsFailedValidationUntilFileChanges() {
        val cache = PathModificationCache<String>(2)
        var loads = 0

        assertEquals(null, cache.getOrLoad("/recordings/broken.mp3", 100) { loads++; null })
        assertEquals(null, cache.getOrLoad("/recordings/broken.mp3", 100) { loads++; "stale" })
        assertEquals("valid", cache.getOrLoad("/recordings/broken.mp3", 200) { loads++; "valid" })
        assertEquals(2, loads)
    }
}
