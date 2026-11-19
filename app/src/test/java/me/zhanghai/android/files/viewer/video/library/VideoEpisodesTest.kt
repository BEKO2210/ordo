/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.zhanghai.android.files.viewer.video.library

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoEpisodesTest {
    private val serien = "/storage/emulated/0/Serien"
    private val movies = "/storage/emulated/0/Movies"

    private fun tag(series: String, season: Int, episode: Int) = EpisodeTag(series, season, episode)

    @Test
    fun parsesSamples() {
        val samples = listOf(
            Triple("Dark.S01E02.German.1080p", "$serien/Dark", tag("Dark", 1, 2)),
            Triple("Breaking Bad - s2e10 - Over", movies, tag("Breaking Bad", 2, 10)),
            Triple("Show S01.E03", movies, tag("Show", 1, 3)),
            Triple("show_s03_e04", movies, tag("show", 3, 4)),
            Triple("Dark.S01E02", "$serien/Dark/Staffel 1", tag("Dark", 1, 2)),
            Triple("DARK S10E100", movies, tag("DARK", 10, 100)),
            Triple("Special S00E00", movies, tag("Special", 0, 0)),
            Triple("S01E02", "$serien/Dark/Staffel 1", tag("Dark", 1, 2)),
            Triple("S01E02", "$serien/Dark", tag("Dark", 1, 2)),
            Triple("The Office 3x07", movies, tag("The Office", 3, 7)),
            Triple("The.Office.3x07.720p", movies, tag("The Office", 3, 7)),
            Triple("Lost 10x101", movies, tag("Lost", 10, 101)),
            Triple("Die Sendung mit der Maus - Staffel 2 - Folge 5", movies, tag("Die Sendung mit der Maus", 2, 5)),
            Triple("Pumuckl Folge 3", movies, tag("Pumuckl", 1, 3)),
            Triple("Löwenzahn Folge 7", movies, tag("Löwenzahn", 1, 7)),
            Triple("Ep. 4 Neue Hoffnung", "/Filme/Star Wars", tag("Star Wars", 1, 4)),
            Triple("Folge 12", "$serien/Pumuckl", tag("Pumuckl", 1, 12)),
            Triple("Folge 12", "$serien/Pumuckl/Staffel 3", tag("Pumuckl", 3, 12)),
            Triple("Staffel 2 Folge 3", "$serien/Tatort", tag("Tatort", 2, 3)),
            Triple("Tatort Folge 12", "$serien/Tatort/Season 02", tag("Tatort", 2, 12)),
            Triple("Tatort Folge 12", "$serien/Tatort/S3", tag("Tatort", 3, 12)),
            Triple("Friends Season 4 Episode 9", movies, tag("Friends", 4, 9)),
            Triple("Friends episode 9", "$serien/Friends/season 5", tag("Friends", 5, 9)),
            Triple("Friends EP12", movies, tag("Friends", 1, 12)),
            Triple("Friends S4 Ep9", movies, tag("Friends", 4, 9)),
            Triple("[SubsPlease] Sousou no Frieren - 03 (1080p) [A1B2C3D4]", movies, tag("Sousou no Frieren", 1, 3)),
            Triple("[Erai-raws] Kimetsu no Yaiba - 12v2 [1080p]", movies, tag("Kimetsu no Yaiba", 1, 12)),
            Triple("[Group] Anime - 05", "$serien/Anime/Staffel 2", tag("Anime", 2, 5)),
            Triple("Anime - 105", movies, tag("Anime", 1, 105)),
            Triple("Dark_S01E02_x", movies, tag("Dark", 1, 2)),
            Triple("Dark (2017) S01E02", movies, tag("Dark", 1, 2)),
            Triple("Dark [DE] - S01E02", movies, tag("Dark", 1, 2)),
            Triple("Step 3 Folge 4", movies, tag("Step 3", 1, 4)),
            Triple("VID_20261118_141941", movies, null),
            Triple("PXL_20261118_141941123", movies, null),
            Triple("20261118_141941", movies, null),
            Triple("Urlaub 2026", movies, null),
            Triple("Konzert", movies, null),
            Triple("Geburtstag Oma", movies, null),
            Triple("Tatort Folge 1234", movies, tag("Tatort", 1, 1234)),
            // Added after the review by agy (Gemini): scene, fansub and German forms; films that must stay films.
            Triple("Dark.E03", "$serien/Dark/Staffel 1", tag("Dark", 1, 3)),
            Triple("[Group]_Frieren_-_03_[1080p]", movies, tag("Frieren", 1, 3)),
            Triple("Dark - 01", "$serien/Dark/Staffel_01", tag("Dark", 1, 1)),
            Triple("Dark - 01", "$serien/Dark/Specials", tag("Dark", 0, 1)),
            Triple("Tannbach - Teil 2", movies, tag("Tannbach", 1, 2)),
            Triple("Dark.1x2", movies, tag("Dark", 1, 2)),
            Triple("Iron Man - 2", movies, null),
            Triple("The Matrix - 720 [BluRay]", movies, null),
            Triple("Movie.2024.10x264", movies, null),
            Triple("IMG_E1234", "/DCIM/Camera", null),
            Triple("Clip 1920x1080", movies, null),
            Triple("Clip 720x480", movies, null),
            Triple("Movie.2019.1080p.x264", movies, null),
            Triple("Step 3", movies, null),
            Triple("S01E02", "", null),
            Triple("Folge 3", "/", null),
        )
        for ((title, folder, expected) in samples) {
            assertEquals("title: $title (folder: $folder)", expected, VideoEpisodes.parse(title, folder))
        }
    }

    @Test
    fun cleanNameStripsNoise() {
        assertEquals("Dark", VideoEpisodes.cleanName("Dark."))
        assertEquals("Breaking Bad", VideoEpisodes.cleanName("Breaking_Bad - "))
        assertEquals("Frieren", VideoEpisodes.cleanName("[SubsPlease] Frieren (2023) "))
        assertEquals("The Office", VideoEpisodes.cleanName("The.Office"))
        assertEquals("A B", VideoEpisodes.cleanName("A   B"))
        assertEquals("", VideoEpisodes.cleanName(" - . "))
    }

    @Test
    fun seriesKeyIgnoresCaseAndSpacing() {
        assertEquals(tag("Dark", 1, 1).seriesKey, tag("dark ", 2, 3).seriesKey)
        assertEquals("die maus", tag("Die   Maus", 1, 1).seriesKey)
    }
}
