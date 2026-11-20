/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.subtitles

import me.zhanghai.android.files.viewer.video.subtitles.SubtitleChoice.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

class SubtitleLogicTest {
    private val deFile = Candidate(SubtitleChoice.File("file:///m/film.de.srt"), "de")
    private val enFile = Candidate(SubtitleChoice.File("file:///m/film.en.srt"), "en")
    private val plainFile = Candidate(SubtitleChoice.File("file:///m/film.srt"), null)
    private val deTrack = Candidate(SubtitleChoice.Track("3", "de"), "de")
    private val deForced = Candidate(SubtitleChoice.Track("4", "de"), "de", forced = true)
    private val enTrack = Candidate(SubtitleChoice.Track("5", "en"), "en")

    @Test
    fun timelineShiftsByDelayAndShowsOverlaps() {
        val timeline = SubtitleTimeline(
            listOf(
                SubtitleTimeline.Entry(2_000_000, 4_000_000, listOf("b")),
                SubtitleTimeline.Entry(1_000_000, 3_000_000, listOf("a"))
            )
        )
        assertEquals(emptyList<String>(), timeline.cuesAt(500_000, 0))
        assertEquals(listOf("a"), timeline.cuesAt(1_000_000, 0))
        assertEquals(listOf("a", "b"), timeline.cuesAt(2_500_000, 0))
        assertEquals(emptyList<String>(), timeline.cuesAt(4_000_000, 0))
        // 0.5 s late: "a" starts at 1.5 s; 1 s early: "b" ends at 3 s.
        assertEquals(emptyList<String>(), timeline.cuesAt(1_200_000, 500_000))
        assertEquals(listOf("a"), timeline.cuesAt(1_500_000, 500_000))
        assertEquals(emptyList<String>(), timeline.cuesAt(3_000_000, -1_000_000))
        assertEquals(SubtitleTimeline.MAX_DELAY_MS, SubtitleTimeline.clampDelay(Long.MAX_VALUE))
    }

    @Test
    fun choicesSurviveEncoding() {
        for (choice in listOf(
            SubtitleChoice.Off, SubtitleChoice.File("file:///a%20b/c.srt"), SubtitleChoice.Track("3", "de"),
            SubtitleChoice.Track(null, null), SubtitleChoice.Track("1:2", null)
        )) {
            assertEquals(choice, SubtitleChoice.decode(choice.encode()))
        }
        assertNull(SubtitleChoice.decode("nonsense"))
        assertNull(SubtitleChoice.decode("file:"))
        assertNull(SubtitleChoice.decode(null))
    }

    @Test
    fun rememberedChoiceWins() {
        val files = listOf(plainFile, deFile)
        val tracks = listOf(deTrack, enTrack)
        assertEquals(SubtitleChoice.Off, SubtitleChoice.pick(SubtitleChoice.Off, true, "de", "en", files, tracks))
        assertEquals(enTrack.choice, SubtitleChoice.pick(enTrack.choice, true, "de", "en", files, tracks))
        // The track's ID changed (another mux): its language still finds it.
        assertEquals(
            enTrack.choice, SubtitleChoice.pick(SubtitleChoice.Track("9", "en"), true, "de", "en", files, tracks)
        )
        // A remembered file that is gone falls back to the automatic pick.
        assertEquals(
            deFile.choice,
            SubtitleChoice.pick(SubtitleChoice.File("file:///gone.srt"), true, "de", "en", files, tracks)
        )
    }

    @Test
    fun automaticPickPrefersFilesInTheLanguage() {
        assertEquals(deFile.choice, SubtitleChoice.pick(null, true, "de", "en", listOf(enFile, deFile), listOf(deTrack)))
        assertEquals(deTrack.choice, SubtitleChoice.pick(null, true, "de", "en", listOf(enFile), listOf(deForced, deTrack)))
        assertEquals(plainFile.choice, SubtitleChoice.pick(null, true, "de", "en", listOf(enFile, plainFile), listOf(enTrack)))
        assertNull(SubtitleChoice.pick(null, true, "de", "en", listOf(enFile), listOf(enTrack)))
        // Unknown sound language still counts as foreign.
        assertEquals(deFile.choice, SubtitleChoice.pick(null, true, "de", null, listOf(deFile), emptyList()))
    }

    @Test
    fun noAutomaticPickWhenOffOrSoundAlreadyInTheLanguage() {
        assertNull(SubtitleChoice.pick(null, false, "de", "en", listOf(deFile), listOf(deTrack)))
        assertNull(SubtitleChoice.pick(null, true, "de", "de", listOf(deFile), listOf(deTrack)))
        assertNull(SubtitleChoice.pick(null, true, "de-DE", "de", listOf(deFile), listOf(deTrack)))
        assertTrue(SubtitleChoice.sameLanguage("pt-BR", "pt"))
    }

    @Test
    fun storeEntriesAndStyleSurviveEncoding() {
        val entry = SubtitleStore.Entry(SubtitleChoice.File("file:///m/film.de.srt"), -1_300, "windows-1252", "14:99")
        assertEquals(entry, SubtitleStore.decode(SubtitleStore.encode(entry, 42)))
        val empty = SubtitleStore.Entry(null)
        assertEquals(empty, SubtitleStore.decode(SubtitleStore.encode(empty, 42)))
        // An unknown charset is dropped, not trusted.
        assertNull(SubtitleStore.decode(SubtitleStore.encode(entry.copy(charset = "x-evil"), 1))!!.charset)
        assertNull(SubtitleStore.decode("broken"))

        val style = SubtitleStyle(
            160, SubtitleStyle.Font.CONDENSED, SubtitleStyle.Background.OUTLINE, SubtitleStyle.Color.CYAN, 14,
            fileStyles = true, system = false
        )
        assertEquals(style, SubtitleStyle.decode(style.encode()))
        assertEquals(SubtitleStyle(), SubtitleStyle.decode("??"))
        assertEquals(SubtitleStyle(), SubtitleStyle.decode(null))
        // Out of range parts fall back one by one.
        assertEquals(SubtitleStyle(), SubtitleStyle.decode("v2,900,X,Y,Z,99,maybe,no"))
    }

    @Test
    fun oldStylesCarryOverAndPlainTextIsGone() {
        // The owner's Tab S9 after a crawler run: extra large, no background, yellow.
        val old = SubtitleStyle.decode("XL,NONE,YELLOW")
        assertEquals(160, old.sizePercent)
        assertEquals(SubtitleStyle.Background.OUTLINE, old.background)
        assertEquals(SubtitleStyle.Color.YELLOW, old.color)
        assertEquals(SubtitleStyle(), SubtitleStyle.decode("M,BOX,WHITE"))
    }

    @Test
    fun stepsStayInRangeAndDefaultsFollowTheBbc() {
        var style = SubtitleStyle()
        repeat(30) { style = style.stepSize(1).stepBottom(1) }
        assertEquals(SubtitleStyle.SIZE_MAX, style.sizePercent)
        assertEquals(SubtitleStyle.BOTTOM_MAX, style.bottomPercent)
        repeat(30) { style = style.stepSize(-1).stepBottom(-1) }
        assertEquals(SubtitleStyle.SIZE_MIN, style.sizePercent)
        assertEquals(SubtitleStyle.BOTTOM_MIN, style.bottomPercent)
        // BBC 9.2.1: a line of 7–8 % of the picture on a phone (text × ~1.17 line spacing); smaller on tablets.
        val phoneLine = SubtitleStyle().textFraction(phone = true) * 1.17f
        assertTrue("$phoneLine", phoneLine in 0.07f..0.08f)
        assertTrue(SubtitleStyle().textFraction(phone = false) < SubtitleStyle().textFraction(phone = true))
    }

    @Test
    fun pictureHeightIsTheLetterboxedVideo() {
        // Tab A8 portrait, 1200 × 1920: the 16:9 picture is 675 px tall.
        assertEquals(675, VideoSubtitles.pictureHeight(1280, 720, 0, 1f, true, 1200, 1920))
        // Landscape 1920 × 1200: 1080 of 1200.
        assertEquals(1080, VideoSubtitles.pictureHeight(1280, 720, 0, 1f, true, 1920, 1200))
        // Zoom and stretch fill the view; an unknown size too.
        assertEquals(1920, VideoSubtitles.pictureHeight(1280, 720, 0, 1f, false, 1200, 1920))
        assertEquals(1920, VideoSubtitles.pictureHeight(0, 0, 0, 1f, true, 1200, 1920))
        // A phone video stored as 1920 × 1080 turned by 90°: upright it fills a portrait screen's height.
        assertEquals(1920, VideoSubtitles.pictureHeight(1920, 1080, 90, 1f, true, 1200, 1920))
        // Anamorphic DVD: 720 × 576 with wide pixels is 16:9.
        assertEquals(675, VideoSubtitles.pictureHeight(720, 576, 0, 64f / 45, true, 1200, 1920))
    }

    @Test
    fun placedLinesCountInThePicture() {
        val top = androidx.media3.common.text.Cue.Builder().setText("Schild")
            .setLine(0.05f, androidx.media3.common.text.Cue.LINE_TYPE_FRACTION).build()
        val plain = androidx.media3.common.text.Cue.Builder().setText("x").build()
        // Portrait A8: picture 675 of 1920 px, starting at 622 px.
        val mapped = VideoSubtitles.intoPicture(listOf(top, plain), 675f / 1920)
        assertEquals((622 + 0.05f * 675) / 1920, mapped[0].line, 0.001f)
        assertEquals(plain, mapped[1])
        assertEquals(listOf(top), VideoSubtitles.intoPicture(listOf(top), 1f))
    }

    @Test
    fun linesTheirFormatPutAtTheBottomTakeOrdosDistance() {
        fun cue(line: Float, type: Int, anchor: Int = androidx.media3.common.text.Cue.TYPE_UNSET) =
            androidx.media3.common.text.Cue.Builder().setText("x").setLine(line, type).setLineAnchor(anchor).build()
        val number = androidx.media3.common.text.Cue.LINE_TYPE_NUMBER
        val fraction = androidx.media3.common.text.Cue.LINE_TYPE_FRACTION
        val end = androidx.media3.common.text.Cue.ANCHOR_TYPE_END
        val start = androidx.media3.common.text.Cue.ANCHOR_TYPE_START
        assertTrue(VideoSubtitles.isAtBottom(cue(-1f, number)))          // WebVTT "auto"
        assertTrue(VideoSubtitles.isAtBottom(cue(0.95f, fraction, end)))  // ASS \an2
        assertTrue(!VideoSubtitles.isAtBottom(cue(0f, number)))           // WebVTT line 0: top
        assertTrue(!VideoSubtitles.isAtBottom(cue(0.05f, fraction, start))) // ASS \an8: top
        assertTrue(!VideoSubtitles.isAtBottom(cue(0.5f, fraction)))       // a sign in the middle
        assertTrue(!VideoSubtitles.isAtBottom(androidx.media3.common.text.Cue.Builder().setText("x").build()))
    }

    @Test
    fun understandBetterPicksPreferredEvenWhenAudioIsInIt() {
        // VideoSubtitles.turnOnForUnderstanding passes no audio language and no remembered choice.
        val de = SubtitleChoice.Candidate(SubtitleChoice.File("file:///f.de.srt"), "de")
        val en = SubtitleChoice.Candidate(SubtitleChoice.Track("1", "en"), "en")
        assertEquals(de.choice, SubtitleChoice.pick(null, true, "de", null, listOf(de), listOf(en)))
        assertEquals(null, SubtitleChoice.pick(null, true, "de", null, emptyList(), listOf(en)))
    }

    @Test
    fun aTrackParsedWhileExtractingKeepsItsKind() {
        val srt = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.APPLICATION_SUBRIP).build()
        val pgs = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.APPLICATION_PGS).build()
        assertEquals(MimeTypes.APPLICATION_SUBRIP, VideoSubtitles.subtitleMimeType(srt))
        assertTrue(VideoSubtitles.isBitmap(pgs))
        assertTrue(!VideoSubtitles.isBitmap(srt))
        assertTrue(VideoSubtitles.isBitmap(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_VOBSUB).build()))
    }
}

