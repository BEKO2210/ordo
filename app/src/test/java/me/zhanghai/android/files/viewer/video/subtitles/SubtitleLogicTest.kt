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
        val entry = SubtitleStore.Entry(SubtitleChoice.File("file:///m/film.de.srt"), -1_300, "windows-1252")
        assertEquals(entry, SubtitleStore.decode(SubtitleStore.encode(entry, 42)))
        val empty = SubtitleStore.Entry(null)
        assertEquals(empty, SubtitleStore.decode(SubtitleStore.encode(empty, 42)))
        // An unknown charset is dropped, not trusted.
        assertNull(SubtitleStore.decode(SubtitleStore.encode(entry.copy(charset = "x-evil"), 1))!!.charset)
        assertNull(SubtitleStore.decode("broken"))

        val style = SubtitleStyle(SubtitleStyle.Size.XL, SubtitleStyle.Background.BOX, SubtitleStyle.Color.YELLOW)
        assertEquals(style, SubtitleStyle.decode(style.encode()))
        assertEquals(SubtitleStyle(), SubtitleStyle.decode("??"))
        assertEquals(SubtitleStyle(), SubtitleStyle.decode(null))
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

