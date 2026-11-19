/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import me.zhanghai.android.files.player.PlayHistoryStore
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicActionsTest {
    private fun song(
        path: String,
        artist: String? = "Band",
        album: String? = "Album",
        genre: String? = null,
        year: Int = 0,
        durationMs: Long = 200_000,
        added: Long = 0,
        modified: Long = 0
    ) = MusicSong(
        path, path.substringAfterLast('/').substringBeforeLast('.'), artist, album, 1, durationMs, 0, "audio/mpeg",
        null, genre, year, added, modified
    )

    private val songs = listOf(
        song("/m/rock/a.mp3", artist = "The Beatles", album = "Abbey Road", genre = "Rock", year = 1969, added = 10),
        song("/m/rock/b.flac", artist = "Queen", genre = "Rock", year = 1975, durationMs = 360_000, added = 30),
        song("/m/jazz/c.mp3", artist = "Miles Davis", album = "Kind of Blue", genre = "Jazz", year = 1959, added = 20,
            modified = 50),
        song("/m/jazz/deep/d.opus", artist = null, album = null, durationMs = 60_000, modified = 40)
    )
    private val history = listOf(
        PlayHistoryStore.Entry("file:///m/jazz/c.mp3", 5, 100),
        PlayHistoryStore.Entry("file:///m/rock/b.flac", 9, 50),
        PlayHistoryStore.Entry("file:///m/gone.mp3", 20, 200)
    )
    private val catalog = MusicCatalog(
        songs, history,
        mapOf(PlaylistStore.FAVORITES_FILE to listOf("/m/jazz/c.mp3", "/m/rock/a.mp3"), "Mix.m3u8" to listOf("/m/jazz/deep/d.opus", "/m/rock/b.flac"))
    )

    private fun names(filter: MusicFilter) = MusicSelection.select(catalog, filter).map { it.title }

    @Test
    fun everyFieldNarrowsTheChoice() {
        assertEquals(listOf("a", "b", "c", "d"), names(MusicFilter()))
        assertEquals(listOf("a"), names(MusicFilter(artist = "beatles")))
        assertEquals(listOf("c"), names(MusicFilter(album = "kind of")))
        assertEquals(listOf("a", "b"), names(MusicFilter(genre = "rock")))
        assertEquals(listOf("b"), names(MusicFilter(year = 1975)))
        assertEquals(listOf("c", "d"), names(MusicFilter(folder = "/m/jazz/")))
        assertEquals(listOf("b"), names(MusicFilter(minDurationMs = 300_000)))
        assertEquals(listOf("d"), names(MusicFilter(maxDurationMs = 90_000)))
        assertEquals(listOf("b"), names(MusicFilter(format = ".FLAC")))
        assertEquals(listOf("a", "b"), names(MusicFilter(genre = "Rock", query = "e")))
        assertEquals(listOf("b"), names(MusicFilter(genre = "Rock", year = 1975)))
    }

    @Test
    fun aSearchLooksAtTitleArtistAlbumAndGenre() {
        assertEquals(listOf("a"), names(MusicFilter(query = " abbey ")))
        assertEquals(listOf("c"), names(MusicFilter(query = "jazz")))
        assertEquals(listOf("c"), names(MusicFilter(query = "KIND")))
        assertEquals(emptyList<String>(), names(MusicFilter(query = "nothing like this")))
    }

    @Test
    fun newAndChangedSongsComeNewestFirst() {
        assertEquals(listOf("b", "c"), names(MusicFilter(addedAfterMs = 20)))
        assertEquals(listOf("c", "d"), names(MusicFilter(modifiedAfterMs = 40)))
    }

    @Test
    fun listsKeepTheirOwnOrder() {
        assertEquals(listOf("c", "a"), names(MusicFilter(favorites = true)))
        assertEquals(listOf("d", "b"), names(MusicFilter(playlist = "Mix.m3u8")))
        assertEquals(emptyList<String>(), names(MusicFilter(playlist = "Missing.m3u8")))
        // Songs that are gone from the library are left out of the history.
        assertEquals(listOf("c", "b"), names(MusicFilter(recentlyPlayed = true)))
        assertEquals(listOf("b", "c"), names(MusicFilter(mostPlayed = true)))
        assertEquals(listOf("b"), names(MusicFilter(mostPlayed = true, limit = 1)))
        assertEquals(listOf("c"), names(MusicFilter(favorites = true, genre = "jazz")))
    }

    @Test
    fun queuedSongsGoAfterTheCurrentOneOrToTheEnd() {
        assertEquals(3, MusicSelection.insertIndex(next = true, currentIndex = 2, count = 5))
        assertEquals(5, MusicSelection.insertIndex(next = false, currentIndex = 2, count = 5))
        // An empty queue (no current item) still takes the songs at the start.
        assertEquals(0, MusicSelection.insertIndex(next = true, currentIndex = -1, count = 0))
        assertEquals(5, MusicSelection.insertIndex(next = true, currentIndex = 4, count = 5))
    }
}
