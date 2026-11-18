/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio.lyrics

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.zhanghai.android.files.viewer.audio.lyrics.SemanticLyrics.SyncedLyrics
import me.zhanghai.android.files.viewer.audio.lyrics.SemanticLyrics.UnsyncedLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ported LRC parser and the position lookups the player uses. */
class LyricsTest {
    private fun synced(lrc: String) = parseLrc(lrc, trimEnabled = true, multiLineEnabled = false) as SyncedLyrics

    private fun lyrics(lrc: String) =
        Lyrics(parseLrc(lrc, trimEnabled = true, multiLineEnabled = false)!!, LyricsSource.SIDECAR_LRC)

    @Test
    fun normalLrc() {
        val lines = synced("[00:01.00]Hallo\n[00:03.50]Welt\n[01:02.345]Ende").text
        assertEquals(listOf("Hallo", "Welt", "Ende"), lines.map { it.text })
        assertEquals(listOf(1000uL, 3500uL, 62345uL), lines.map { it.start })
        assertEquals(3499uL, lines[0].end)
    }

    @Test
    fun enhancedLrcHasWordTimes() {
        val line = synced("[00:01.00]<00:01.00>Hal <00:01.50>lo <00:02.00>\n[00:04.00]Nächste").text[0]
        assertEquals("Hal lo", line.text)
        val words = line.words!!
        assertEquals(listOf(1000uL, 1500uL), words.map { it.begin })
        assertEquals(listOf("Hal", "lo"), words.map { line.text.substring(it.charRange) })
        assertEquals(1499uL, words[0].endInclusive)
    }

    @Test
    fun severalTimestampsRepeatALine() {
        val lines = synced("[00:01.00][00:05.00]Refrain\n[00:03.00]Strophe").text
        assertEquals(listOf("Refrain", "Strophe", "Refrain"), lines.map { it.text })
        assertEquals(listOf(1000uL, 3000uL, 5000uL), lines.map { it.start })
    }

    @Test
    fun offsetShiftsAndMetadataIsNoText() {
        val lines = synced("[ar:Künstler]\n[ti:Titel]\n[offset:500]\n[00:01.00]A\n[00:00.20]B").text
        // A positive offset shows lyrics earlier; times never go below 0.
        assertEquals(listOf("B", "A"), lines.map { it.text })
        assertEquals(listOf(0uL, 500uL), lines.map { it.start })
    }

    @Test
    fun malformedLinesAreSkipped() {
        val lines = synced("[00:01.00]A\n[00:xx]kaputt\n<<>>\n[00:02.00]B").text
        assertEquals(listOf("A", "B"), lines.map { it.text })
    }

    @Test
    fun emptyAndPlainText() {
        assertNull(parseLrc("", trimEnabled = true, multiLineEnabled = false))
        assertNull(parseLrc(" \n ", trimEnabled = true, multiLineEnabled = false))
        val plain = parseLrc("Erste Zeile\nZweite Zeile", trimEnabled = true, multiLineEnabled = false)
        assertTrue(plain is UnsyncedLyrics)
        val lyrics = Lyrics(plain!!, LyricsSource.EMBEDDED_UNSYNCED)
        assertFalse(lyrics.isSynced)
        assertEquals(listOf("Erste Zeile", "Zweite Zeile"), lyrics.text)
        assertEquals(-1, lyrics.lineAt(5000))
        assertEquals(-1, lyrics.wordAt(5000))
    }

    @Test
    fun lineAtBoundaries() {
        val lyrics = lyrics("[00:01.00]A\n[00:03.50]B")
        assertEquals(-1, lyrics.lineAt(-1))
        assertEquals(-1, lyrics.lineAt(999))
        assertEquals(0, lyrics.lineAt(1000))
        assertEquals(0, lyrics.lineAt(3499))
        assertEquals(1, lyrics.lineAt(3500))
        assertEquals(1, lyrics.lineAt(Long.MAX_VALUE))
    }

    @Test
    fun lineAtPicksTheMainLineOverItsTranslation() {
        val lyrics = lyrics("[00:01.00]Hallo\n[00:01.00]Hello\n[00:02.00]Weiter")
        assertEquals(0, lyrics.lineAt(1500))
        assertTrue(lyrics.lines[1].isTranslated)
        assertEquals(2, lyrics.lineAt(2000))
    }

    @Test
    fun wordAtFollowsTheWords() {
        val lyrics = lyrics("[00:01.00]<00:01.00>Hal <00:01.50>lo <00:02.00>\n[00:04.00]X")
        assertEquals(-1, lyrics.wordAt(900))
        assertEquals(0, lyrics.wordAt(1000))
        assertEquals(0, lyrics.wordAt(1499))
        assertEquals(1, lyrics.wordAt(1600))
        assertEquals(-1, lyrics.wordAt(4100))
    }

    @Test
    fun positionFlowEmitsOnlyLineChanges() = runBlocking {
        val lyrics = lyrics("[00:01.00]A\n[00:03.50]B")
        val indices = flowOf(0L, 500L, 1000L, 1200L, 3500L, 4000L).lyricsLineIndex(lyrics).toList()
        assertEquals(listOf(-1, 0, 1), indices)
    }
}
