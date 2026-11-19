/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The acceptance of WISSENSCHAFT.md 3.2 (1, 3, 4): reading aid, contrast, SDH. */
class ReadingAidTest {
    private fun entry(startMs: Long, endMs: Long, text: String) =
        SubtitleTimeline.Entry(startMs * 1000, endMs * 1000, listOf(text))

    private fun extend(cps: Int, vararg entries: SubtitleTimeline.Entry<String>) =
        ReadingAid.extend(entries.toList(), cps) { it.joinToString("\n") }

    @Test
    fun charactersPerSecond() {
        // Spaces count, a line break counts as one space, runs of spaces as one.
        assertEquals(11, ReadingAid.characters("Hello world"))
        assertEquals(11, ReadingAid.characters("Hello\nworld"))
        assertEquals(11, ReadingAid.characters("  Hello   world "))
        assertEquals(17.0, ReadingAid.cps("a".repeat(17), 1_000_000), 1e-9)
        assertEquals(30.0, ReadingAid.cps("a".repeat(60), 2_000_000), 1e-9)
        assertEquals(2, ReadingAid.words("Hello\nworld"))
        assertEquals(2, ReadingAid.lines("Hello\nworld\n"))
        assertEquals(2, ReadingAid.characters("\uD83D\uDE00!"))
        assertEquals(2, ReadingAid.words("10\u00A0km"))
        assertEquals(17, ReadingAid.level("nonsense"))
        assertEquals(0, ReadingAid.level("0"))
    }

    @Test
    fun readingTimeIsTheLongestOfCharactersWordsAndLines() {
        // 34 characters at 17/s = 2 s; 6 words = 1.8 s; 1 line = 1 s.
        assertEquals(2_000_000L, ReadingAid.readingTimeUs("a".repeat(4) + " bbbbb".repeat(5), 17))
        // "Ja ja ja ja ja": 14 characters = 0.82 s, 5 words = 1.5 s.
        assertEquals(1_500_000L, ReadingAid.readingTimeUs("Ja ja ja ja ja", 17))
        // "Oh": one line = 1 s.
        assertEquals(1_000_000L, ReadingAid.readingTimeUs("Oh", 17))
        // Two lines = 2 s.
        assertEquals(2_000_000L, ReadingAid.readingTimeUs("Oh\nAh", 17))
        assertEquals(0L, ReadingAid.readingTimeUs("", 17))
    }

    @Test
    fun fastLinesStayButNeverOverlapTheNext() {
        // 60 characters in 2 s = 30/s: wants 60/17 = 3.53 s, the next line starts at 3 s.
        val fast = "x".repeat(29) + " " + "y".repeat(30)
        val out = extend(17, entry(0, 2_000, fast), entry(3_000, 9_000, "Next line here"))
        assertEquals(3_000_000L, out[0].endUs)
        assertTrue(out[0].fast)
        assertFalse(out[1].fast)
        for (i in 0 until out.size - 1) assertTrue(out[i].endUs <= out[i + 1].startUs)
        // With room it stays exactly its reading time.
        val free = extend(17, entry(0, 2_000, fast), entry(10_000, 12_000, "Next"))
        assertEquals(3_529_412L, free[0].endUs)
    }

    @Test
    fun everyLineGetsThreeTenthsPerWordAndOneSecondPerLine() {
        // 30 characters per second lines, 0.5 s each, 8 s apart: each stays ≥ 0.3 s per word and ≥ 1 s.
        val texts = listOf("Ich gehe jetzt nach Hause, ok?", "Warte doch mal auf mich bitte!", "Nein")
        val out = extend(20, *texts.mapIndexed { i, t -> entry(i * 8_000L, i * 8_000L + 500, t) }.toTypedArray())
        for ((i, e) in out.withIndex()) {
            val shown = e.endUs - e.startUs
            assertTrue("line $i: $shown", shown >= ReadingAid.words(texts[i]) * ReadingAid.WORD_US)
            assertTrue("line $i: $shown", shown >= ReadingAid.LINE_US)
        }
    }

    @Test
    fun offAndSlowEnoughLinesAreUntouchedAndOverlapsStay() {
        val slow = entry(0, 4_000, "Hello")
        assertEquals(4_000_000L, extend(17, slow)[0].endUs)
        assertEquals(500_000L, extend(0, entry(0, 500, "Hello there friend"))[0].endUs)
        // The file overlaps on purpose (two speakers): nothing is shortened.
        val out = extend(17, entry(0, 3_000, "A"), entry(1_000, 2_000, "B"))
        assertEquals(3_000_000L, out[0].endUs)
        // Segments of one line (WebVTT/TTML) count as one: not "fast".
        val segments = extend(17, entry(0, 1_000, "Long line of text here"), entry(1_000, 3_000, "Long line of text here"))
        assertEquals(1, segments.size)
        assertFalse(segments[0].fast)
    }

    @Test
    fun boxKeepsWcagContrastOverAnyPicture() {
        val backgrounds = listOf(
            0xFFFFFFFF, 0xFF000000, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00, 0xFF00FFFF, 0xFFFF00FF,
            0xFF808080, 0xFFC0C0C0
        ).map { it.toInt() }
        for (color in SubtitleStyle.Color.values()) {
            val alpha = SubtitleStyle.boxAlpha(color.argb)
            for (background in backgrounds) {
                val ratio = SubtitleStyle.contrast(color.argb, SubtitleStyle.overBackground(alpha, background))
                assertTrue("$color over ${Integer.toHexString(background)}: $ratio", ratio >= SubtitleStyle.MIN_CONTRAST)
            }
        }
        assertEquals(21.0, SubtitleStyle.contrast(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0.01)
        assertEquals(SubtitleStyle.Background.BOX, SubtitleStyle().background)
    }

    @Test
    fun soundDescriptionsAndSpeakers() {
        fun hide(text: String): String {
            val first = Sdh.delete(text, Sdh.soundRanges(text))
            return Sdh.delete(first, Sdh.emptyLineRanges(first))
        }
        assertEquals("Hello there", hide("Hello [laughs] there"))
        assertEquals("Wait!", hide("[MUSIK]\nWait!"))
        assertEquals("Wait!", hide("Wait!\n(DOOR SLAMS)"))
        assertEquals("", hide("[MUSIC]").trim())
        // Lower-case parentheses are dialogue.
        assertEquals("I (think) so", hide("I (think) so"))
        assertEquals("- Go!", hide("- Go!\n- [BANG]"))

        val speakers = Sdh.speakers("ANNA: Hi\n- DR. MÜLLER: Hallo\nnot: this")
        assertEquals(listOf("ANNA", "DR. MÜLLER"), speakers.map { it.second })
        assertEquals(0..3, speakers[0].first)
        val colors = Sdh.SpeakerColors()
        assertEquals(Sdh.SPEAKER_COLORS[0], colors.colorOf("ANNA"))
        assertEquals(Sdh.SPEAKER_COLORS[1], colors.colorOf("BEN"))
        assertEquals(Sdh.SPEAKER_COLORS[0], colors.colorOf("ANNA"))
    }
}
