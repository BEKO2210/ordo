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
    fun hugeFilesAreCapped() {
        // 20,000 timed lines, as a crafted 512 KB .lrc could hold: the player gets at most 5,000.
        val lrc = (0 until 20_000).joinToString("\n") { "[%02d:%02d.00]Zeile $it".format(it / 60 % 100, it % 60) }
        val capped = lyrics(lrc)
        assertEquals(MAX_LINES, capped.lines.size)
        assertEquals(MAX_LINES, capped.text.size)
    }

    @Test
    fun parserGetsOnlyTheFirstLines() {
        val text = (0 until 20_000).joinToString("\n") { "[00:00.00]x$it" }
        val capped = LyricsRepository.capInput(text)
        assertEquals(LyricsRepository.MAX_INPUT_LINES, capped.count { it == '\n' })
        assertEquals("kurz", LyricsRepository.capInput("kurz"))
    }

    @Test
    fun longLinesAreCutAtAWordBoundary() {
        val long = (1..200).joinToString(" ") { "Wort$it" }
        val line = lyrics("[00:01.00]$long").lines.single().text
        assertTrue(line.length <= MAX_LINE_CHARS + 2)
        assertTrue(line.endsWith(" …"))
        val kept = line.removeSuffix(" …")
        assertTrue(long.startsWith(kept) && long[kept.length] == ' ')
        assertEquals("kurz", capLine("kurz"))
    }

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
    fun strayMarksStayText() {
        assertEquals(listOf("a<b [c", "d"), synced("[00:01.00]a<b [c\n[00:02.00]d").text.map { it.text })
        assertEquals(listOf("x<y[z]"), synced("[00:01.00]x<y[z]").text.map { it.text })
        assertEquals(listOf("<", "<<a"), synced("[00:01.00]<\n[00:02.00]<<a").text.map { it.text })
        assertEquals(
            "x<y[z", (parseLrc("x<y[z", trimEnabled = true, multiLineEnabled = false) as UnsyncedLyrics)
                .unsyncedText.single().first
        )
    }

    @Test(timeout = 5_000)
    fun longLineOfStrayMarksIsLinear() {
        // A crafted 512 KB single-line .lrc: one token per '<'.
        val line = synced("[00:01.00]" + "<".repeat(512 * 1024)).text.single()
        assertEquals(512 * 1024, line.text.length)
    }

    @Test
    fun positionFlowEmitsOnlyLineChanges() = runBlocking {
        val lyrics = lyrics("[00:01.00]A\n[00:03.50]B")
        val indices = flowOf(0L, 500L, 1000L, 1200L, 3500L, 4000L).lyricsLineIndex(lyrics).toList()
        assertEquals(listOf(-1, 0, 1), indices)
    }
}
