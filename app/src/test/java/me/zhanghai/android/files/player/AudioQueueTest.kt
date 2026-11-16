/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import java8.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioQueueTest {
    @Test
    fun keepsListOrderWithinTappedFolder() {
        val first = Paths.get("/Music/first.mp3")
        val otherFolder = Paths.get("/Podcasts/episode.mp3")
        val tapped = Paths.get("/Music/second.ogg")
        val queue = audioQueue(listOf(first, otherFolder, tapped), tapped)!!
        assertEquals(listOf(first, tapped), queue.paths)
        assertEquals(1, queue.startIndex)
    }

    @Test
    fun missingTappedPathDoesNotCreateAQueue() {
        assertNull(audioQueue(listOf(Paths.get("/Music/one.mp3")), Paths.get("/Music/two.mp3")))
    }
}
