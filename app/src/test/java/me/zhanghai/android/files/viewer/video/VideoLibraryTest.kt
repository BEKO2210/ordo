/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

import java.text.Collator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoLibraryTest {
    private fun video(path: String, added: Long, size: Long = 100) =
        VideoItem(path, path.substringAfterLast('/'), 60_000L, size, added)

    private val a = video("/Movies/Serie/Folge 2.mp4", 3)
    private val b = video("/Movies/Serie/Folge 1.mp4", 1)
    private val c = video("/DCIM/Camera/clip.mp4", 5)
    private val hidden = video("/Movies/.private/x.mp4", 9)
    private val library = VideoLibrary(listOf(a, b, c, hidden, a), false, Collator.getInstance(Locale.GERMAN))

    @Test
    fun newestFirstWithoutHiddenOrDoubles() {
        assertEquals(listOf(c, a, b), library.videos)
        assertEquals(4, VideoLibrary(listOf(a, b, c, hidden), true, Collator.getInstance()).videos.size)
    }

    @Test
    fun sortModesAndHiddenFolders() {
        fun order(sort: VideoPrefs.Sort, hidden: Set<String> = emptySet()) =
            VideoLibrary(
                listOf(
                    a.copy(size = 50, durationMs = 9), b.copy(size = 70, durationMs = 5),
                    c.copy(size = 60, durationMs = 7)
                ),
                false, Collator.getInstance(Locale.GERMAN), sort, hidden
            ).videos.map { it.path }
        assertEquals(listOf(c.path, a.path, b.path), order(VideoPrefs.Sort.NEWEST))
        assertEquals(listOf(c.path, b.path, a.path), order(VideoPrefs.Sort.NAME))
        assertEquals(listOf(b.path, c.path, a.path), order(VideoPrefs.Sort.SIZE))
        assertEquals(listOf(a.path, c.path, b.path), order(VideoPrefs.Sort.LENGTH))
        assertEquals(listOf(c.path), order(VideoPrefs.Sort.NEWEST, setOf("/Movies/Serie")))
    }

    @Test
    fun foldersFollowTheirNewestVideo() {
        assertEquals(listOf("Camera", "Serie"), library.folders.map { it.name })
        assertEquals(listOf(a, b), library.folders[1].videos)
    }

    @Test
    fun cameraNamesGiveTheirMoment() {
        assertEquals(listOf(2026, 11, 18, 14, 19), VideoLibrary.cameraTime("VID_20261118_141941_123")?.toList())
        assertEquals(listOf(2026, 11, 18, 14, 19), VideoLibrary.cameraTime("PXL_20261118_141941123")?.toList())
        assertEquals(listOf(2026, 1, 2, 3, 4), VideoLibrary.cameraTime("20260102_030405")?.toList())
        assertEquals(null, VideoLibrary.cameraTime("Urlaub 2026"))
        assertEquals(null, VideoLibrary.cameraTime("VID_20261318_141941"))
        assertEquals(null, VideoLibrary.cameraTime("VID_20260231_120000"))
    }

    @Test
    fun theQueueIsTheFolderByName() {
        assertEquals(listOf(b, a), library.queueOf(a))
    }

    @Test
    fun continueWatchingMatchesPathAndSizeLastWatchedFirst() {
        val entries = listOf(
            VideoResumeStore.Entry("file:///Movies/Serie/Folge%201.mp4", 100, 20_000, savedAtMs = 1),
            VideoResumeStore.Entry("file:///DCIM/Camera/clip.mp4", 100, 30_000, savedAtMs = 2),
            // Replaced by a file of another size: its place no longer counts.
            VideoResumeStore.Entry("file:///Movies/Serie/Folge%202.mp4", 999, 10_000, savedAtMs = 3),
            VideoResumeStore.Entry("content://x/y", 100, 10_000, savedAtMs = 4),
            // One second left: finished, never "1 s left".
            VideoResumeStore.Entry("file:///Movies/Serie/Folge%201.mp4", 100, 59_000, savedAtMs = 0)
        )
        assertEquals(
            listOf(ContinueItem(c, 30_000), ContinueItem(b, 20_000)),
            library.continueWatching(entries)
        )
    }
}
