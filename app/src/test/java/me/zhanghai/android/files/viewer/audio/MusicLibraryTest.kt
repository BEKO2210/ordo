/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import java.text.Collator
import java.util.Locale
import kotlin.random.Random
import me.zhanghai.android.files.player.queueWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicLibraryTest {
    private fun song(
        path: String,
        title: String,
        artist: String? = "Band",
        album: String? = "Album",
        albumId: Long = 1,
        track: Int = 0,
        mimeType: String = "audio/mpeg"
    ) = MusicSong(path, title, artist, album, albumId, 1_000, track, mimeType, null)

    private val collator = Collator.getInstance(Locale.GERMAN)

    private fun library(vararg songs: MusicSong, showHidden: Boolean = false) =
        MusicLibrary(songs.toList(), showHidden, collator)

    @Test
    fun songsSortByTitleWithTheLocalesCollation() {
        val lib = library(
            song("/m/1.mp3", "Zebra"), song("/m/2.mp3", "Äpfel"), song("/m/3.mp3", "apfel"), song("/m/4.mp3", "Birne")
        )
        // Plain String order would put "Zebra" before "apfel" and "Äpfel" last.
        assertEquals(listOf("apfel", "Äpfel", "Birne", "Zebra"), lib.songs.map { it.title })
    }

    @Test
    fun onlyPlayableFilesAreListed() {
        val lib = library(
            song("/m/a.mp3", "A"), song("/m/b.mid", "B", mimeType = "audio/midi"),
            song("/m/c.flac", "C", mimeType = "audio/flac")
        )
        assertEquals(listOf("A", "C"), lib.songs.map { it.title })
    }

    @Test
    fun hiddenFilesFollowTheSetting() {
        val songs = arrayOf(song("/m/a.mp3", "A"), song("/m/.private/b.mp3", "B"), song("/m/.c.mp3", "C"))
        assertEquals(listOf("A"), library(*songs).songs.map { it.title })
        assertEquals(listOf("A", "B", "C"), library(*songs, showHidden = true).songs.map { it.title })
    }

    @Test
    fun aFileOnTwoVolumesIsListedOnce() {
        assertEquals(1, library(song("/m/a.mp3", "A"), song("/m/a.mp3", "A")).songs.size)
    }

    @Test
    fun albumsGroupByIdAndListTracksInOrder() {
        val lib = library(
            song("/m/3.mp3", "Third", album = "Blue", albumId = 7, track = 3),
            song("/m/1.mp3", "First", album = "Blue", albumId = 7, track = 1),
            song("/m/x.mp3", "Other", album = "Alpha", albumId = 9, artist = "Solo"),
            song("/m/2.mp3", "Second", album = "Blue", albumId = 7, track = 2, artist = "Guest")
        )
        assertEquals(listOf("Alpha", "Blue"), lib.albums.map { it.name })
        val blue = lib.albums.single { it.key == "7" }
        assertEquals(listOf("First", "Second", "Third"), blue.songs.map { it.title })
        // The album's artist is the one most of its songs name, not the guest of one track.
        assertEquals("Band", blue.artist)
    }

    @Test
    fun artistsGroupWithUnknownLast() {
        val lib = library(
            song("/m/1.mp3", "One", artist = "Zeta"), song("/m/2.mp3", "Two", artist = null),
            song("/m/3.mp3", "Three", artist = "Abba"), song("/m/4.mp3", "Four", artist = "Abba")
        )
        assertEquals(listOf("Abba", "Zeta", null), lib.artists.map { it.name })
        assertEquals(listOf("Four", "Three"), lib.artists.first().songs.map { it.title })
    }

    @Test
    fun foldersGroupByParentWithTheirPath() {
        val lib = library(
            song("/s/Music/Rock/a.mp3", "A"), song("/s/Download/b.mp3", "B"), song("/s/Music/Rock/c.mp3", "C")
        )
        assertEquals(listOf("Download", "Rock"), lib.folders.map { it.name })
        assertEquals("/s/Music/Rock", lib.folders.last().key)
        assertEquals(2, lib.folders.last().songs.size)
    }

    @Test
    fun searchMatchesTitleArtistAlbumAndFolderPath() {
        val s = song("/s/Music/Jazz/a.mp3", "Night Train", artist = "Oscar", album = "Blues")
        assertTrue(MusicLibrary.matches(s, "train"))
        assertTrue(MusicLibrary.matches(s, "OSCAR"))
        assertTrue(MusicLibrary.matches(s, " blues "))
        assertTrue(MusicLibrary.matches(s, ""))
        assertTrue(!MusicLibrary.matches(s, "rock"))
        val folder = library(s).folders.single()
        assertTrue(MusicLibrary.matches(folder, "music/jazz"))
        // An album's key is MediaStore's number: searching digits must not find every album.
        val album = library(s).albums.single()
        assertTrue(!MusicLibrary.matches(album, "1"))
    }

    @Test
    fun shuffleContainsEverySongOnce() {
        val songs = (1..50).map { song("/m/$it.mp3", "Song $it") }
        val shuffled = MusicLibrary.shuffled(songs, Random(42))
        assertEquals(songs.size, shuffled.size)
        assertEquals(songs.toSet(), shuffled.toSet())
        assertNotEquals(songs, shuffled)
    }

    @Test
    fun longListsQueueAWindowAroundTheTappedSong() {
        assertEquals(0 until 10, queueWindow(10, 9, maxSize = 1000))
        assertEquals(500 until 1500, queueWindow(3000, 1000, maxSize = 1000))
        assertEquals(2000 until 3000, queueWindow(3000, 2999, maxSize = 1000))
        assertEquals(0 until 1000, queueWindow(3000, 0, maxSize = 1000))
    }

    @Test
    fun emptyLibraryHasNoGroups() {
        val lib = library()
        assertTrue(lib.songs.isEmpty() && lib.albums.isEmpty() && lib.artists.isEmpty() && lib.folders.isEmpty())
        assertNull(lib.albums.firstOrNull())
    }
}
