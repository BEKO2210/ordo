/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.library

import java.io.File
import java.text.Collator
import java.util.Locale
import me.zhanghai.android.files.viewer.video.ContinueItem
import me.zhanghai.android.files.viewer.video.VideoItem
import me.zhanghai.android.files.viewer.video.VideoResumeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoShelvesTest {
    private fun video(path: String, added: Long = 0, durationMs: Long = 25 * 60_000L, size: Long = 100) =
        VideoItem(path, path.substringAfterLast('/').substringBeforeLast('.'), durationMs, size, added)

    private val s1e2 = video("/Serien/Dark/Staffel 1/Dark.S01E02.mkv", 5)
    private val s1e1 = video("/Serien/Dark/Staffel 1/Dark.S01E01.mkv", 4)
    private val s2e1 = video("/Serien/Dark/Staffel 2/Dark S02E01.mkv", 9)
    private val maus3 = video("/Kinder/Maus/Folge 3.mp4", 2)
    private val maus10 = video("/Kinder/Maus/Folge 10.mp4", 1)
    private val lonely = video("/Movies/Konzert - 12.mp4", 3, durationMs = 90 * 60_000L)
    private val film = video("/Movies/Der Film.mkv", 7, durationMs = 41 * 60_000L)
    private val clip = video("/DCIM/Camera/VID_20261118_141941.mp4", 8, durationMs = 30_000L)
    private val all = listOf(s2e1, clip, film, s1e2, s1e1, lonely, maus3, maus10)
    private val shelves = VideoShelves(all, Collator.getInstance(Locale.GERMAN))

    @Test
    fun groupsSeriesSeasonsAndEpisodesInOrder() {
        assertEquals(listOf("Dark", "Maus"), shelves.series.map { it.name })
        val dark = shelves.series.first()
        assertEquals(listOf(1, 2), dark.seasons.map { it.number })
        assertEquals(listOf(s1e1, s1e2), dark.seasons[0].episodes.map { it.video })
        // Numbers, not text: Folge 3 before Folge 10.
        assertEquals(listOf(maus3, maus10), shelves.series[1].episodes.map { it.video })
    }

    @Test
    fun singleMatchIsNoSeriesAndLongVideosAreFilms() {
        assertNull(shelves.seriesOf(lonely))
        assertEquals(listOf(film, lonely), shelves.films)
        assertFalse(clip in shelves.films)
    }

    @Test
    fun seasonQueueAndNextEpisode() {
        assertEquals(listOf(s1e1, s1e2), shelves.seasonQueue(s1e2))
        assertNull(shelves.seasonQueue(film))
        assertEquals(s1e2, shelves.nextEpisode(s1e1))
        // The last of a season goes on with the next season.
        assertEquals(s2e1, shelves.nextEpisode(s1e2))
        assertNull(shelves.nextEpisode(s2e1))
    }

    @Test
    fun recentlyAddedIsNewestFirstWithinThirtyDays() {
        val day = 24 * 60 * 60 * 1000L
        val now = 100 * day
        val recent = VideoShelves(
            listOf(video("/a.mp4", now - 40 * day), video("/b.mp4", now - day), video("/c.mp4", now - 2 * day)),
            Collator.getInstance()
        ).recentlyAdded(now)
        assertEquals(listOf("/b.mp4", "/c.mp4"), recent.map { it.path })
    }

    @Test
    fun finishedRule() {
        val hour = 60 * 60_000L
        assertTrue(VideoShelves.isFinished((hour * 0.95).toLong(), hour))
        assertTrue(VideoShelves.isFinished(hour - 9_000, hour))
        assertFalse(VideoShelves.isFinished(hour / 2, hour))
        assertFalse(VideoShelves.isFinished(0, hour))
        assertFalse(VideoShelves.isFinished(5_000, 0))
    }

    @Test
    fun watchedKeyFromResumeKeyOnlyForLocalFiles() {
        val uri = File("/storage/emulated/0/Movies/a b.mp4").toURI().toString()
        assertEquals(
            "/storage/emulated/0/Movies/a b.mp4|1234",
            VideoShelves.watchedKeyFromResumeKey(VideoResumeStore.key(uri, 1234, 99))
        )
        assertNull(VideoShelves.watchedKeyFromResumeKey(VideoResumeStore.key("smb://host/a.mp4", 1, 2)))
        assertNull(VideoShelves.watchedKeyFromResumeKey("garbage"))
    }

    private fun entry(video: VideoItem, position: Long, at: Long) =
        VideoResumeStore.Entry(File(video.path).toURI().toString(), video.size, position, at)

    @Test
    fun continueCardsAddNextEpisodeAndDropPlacesMarkedWatched() {
        val watched = mapOf(VideoShelves.watchedKey(s1e1) to 50L, VideoShelves.watchedKey(film) to 60L)
        val entries = listOf(entry(film, 600_000, 40), entry(maus3, 100_000, 30))
        val resumed = listOf(ContinueItem(film, 600_000), ContinueItem(maus3, 100_000))
        val cards = shelves.continueCards(resumed, entries, watched)
        // The film was marked watched after its place was saved: gone. Dark's next episode comes first (time 50).
        assertEquals(listOf(s1e2, maus3), cards.map { it.video })
        assertNull(cards[0].positionMs)
        assertEquals(2, cards[0].episode?.episode)
        assertEquals(100_000L, cards[1].positionMs)
    }

    @Test
    fun noNextEpisodeWhileOneOfTheSeriesIsInProgress() {
        val watched = mapOf(VideoShelves.watchedKey(s1e1) to 50L)
        val cards = shelves.continueCards(
            listOf(ContinueItem(s2e1, 20_000)), listOf(entry(s2e1, 20_000, 70)), watched
        )
        assertEquals(listOf(s2e1), cards.map { it.video })
    }

    @Test
    fun searchMatchesTitleFolderAndSeriesIgnoringCaseAndAccents() {
        val loewe = video("/Kinder/Löwenzahn/Folge 7.mp4")
        assertTrue(VideoShelves.matches(loewe, "Löwenzahn", "lowenzahn"))
        assertTrue(VideoShelves.matches(loewe, null, "LÖWENZAHN folge"))
        assertTrue(VideoShelves.matches(s1e2, "Dark", "dark s01e02"))
        assertTrue(VideoShelves.matches(clip, null, "camera"))
        assertFalse(VideoShelves.matches(clip, null, "dark"))
        assertTrue(VideoShelves.matches(clip, null, "  "))
    }
}
