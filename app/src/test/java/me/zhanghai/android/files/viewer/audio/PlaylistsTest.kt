/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaylistsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val music get() = File(folder.root, "Music")
    private val store get() = PlaylistStore(File(music, "Playlists"))
    private fun song(relative: String) = File(music, relative).path

    @Test
    fun writesRelativePathsAndReadsThemBack() {
        val songs = listOf(song("Rock/Grüße – Zoë.mp3"), song("Jazz/Night.flac"))
        store.write("Mix.m3u8", songs)
        val text = File(store.dir, "Mix.m3u8").readText()
        assertTrue(text.startsWith("#EXTM3U\n"))
        assertTrue("relative: $text", text.contains("../Rock/Grüße – Zoë.mp3\n"))
        assertEquals(songs, store.read("Mix.m3u8"))
    }

    @Test
    fun readsWhatOtherAppsWrite() {
        store.dir.mkdirs()
        File(store.dir, "Other.m3u").writeText(
            "﻿#EXTM3U\r\n#EXTINF:123,Band - Song\r\n/storage/emulated/0/Music/a.mp3\r\n\r\n" +
                "..\\Rock\\b.mp3\r\nfile:///sdcard/c.mp3\r\n./d.mp3\r\n"
        )
        assertEquals(
            listOf("/storage/emulated/0/Music/a.mp3", song("Rock/b.mp3"), "/sdcard/c.mp3", File(store.dir, "d.mp3").path),
            store.read("Other.m3u")
        )
    }

    @Test
    fun addKeepsOrderAndSkipsDoubles() {
        assertEquals(2, store.add("Mix.m3u8", listOf(song("a.mp3"), song("b.mp3"), song("a.mp3"))))
        assertEquals(1, store.add("Mix.m3u8", listOf(song("b.mp3"), song("c.mp3"))))
        assertEquals(listOf(song("a.mp3"), song("b.mp3"), song("c.mp3")), store.read("Mix.m3u8"))
        store.remove("Mix.m3u8", listOf(song("b.mp3")))
        assertEquals(listOf(song("a.mp3"), song("c.mp3")), store.read("Mix.m3u8"))
    }

    @Test
    fun favoritesToggleAndComeFirst() {
        assertTrue(store.toggleFavorite(song("a.mp3")))
        assertTrue(store.isFavorite(song("a.mp3")))
        store.write("Abend.m3u8", emptyList())
        assertEquals(listOf(PlaylistStore.FAVORITES_FILE, "Abend.m3u8"), store.names())
        assertFalse(store.toggleFavorite(song("a.mp3")))
        assertFalse(store.isFavorite(song("a.mp3")))
    }

    @Test
    fun noTempFileIsLeftBehind() {
        store.write("Mix.m3u8", listOf(song("a.mp3")))
        assertEquals(listOf("Mix.m3u8"), store.dir.list()!!.toList())
    }

    @Test
    fun fileNamesFromTitles() {
        assertEquals("Party 2026.m3u8", PlaylistStore.fileName("  Party 2026 "))
        assertEquals("ab.m3u8", PlaylistStore.fileName("a/b"))
        assertEquals("hidden.m3u8", PlaylistStore.fileName("..hidden"))
        assertNull(PlaylistStore.fileName(" / "))
        assertEquals("Party", PlaylistStore.title("Party.m3u8"))
    }

    @Test
    fun pathsAreNormalized() {
        assertEquals("/a/c/d", M3u.normalize("/a/b/../c/./d"))
        assertEquals("../Rock/a.mp3", M3u.relative("/m/Rock/a.mp3", "/m/Playlists"))
        assertEquals("a.mp3", M3u.relative("/m/Playlists/a.mp3", "/m/Playlists"))
    }
}
