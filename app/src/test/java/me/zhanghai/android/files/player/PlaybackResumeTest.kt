/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResumeTest {
    private val state = PlaybackStateStore.State(listOf("a", "b", "c"), currentIndex = 1, positionMs = 5_000L)

    @Test
    fun anUntouchedQueueComesBackWithItsPosition() {
        assertEquals(state, restorableState(state) { true })
    }

    @Test
    fun aMissingCurrentFileMovesToTheNextOneAtTheStart() {
        val restored = restorableState(state) { it != "b" }!!
        assertEquals(listOf("a", "c"), restored.paths)
        assertEquals(1, restored.currentIndex)
        assertEquals(0L, restored.positionMs)
    }

    @Test
    fun whenOnlyEarlierFilesAreLeftTheLastOneStarts() {
        val restored = restorableState(state) { it == "a" }!!
        assertEquals(listOf("a"), restored.paths)
        assertEquals(0, restored.currentIndex)
        assertEquals(0L, restored.positionMs)
    }

    @Test
    fun nothingLeftMeansNothingToResume() {
        assertNull(restorableState(state) { false })
    }

    @Test
    fun mediaButtonChecksOnlyLocalPathsAndKeepsNetworkQueues() {
        var checked = false
        assertTrue(mediaButtonQueueItemCanResume("smb") { checked = true; false })
        assertFalse(checked)
        assertFalse(mediaButtonQueueItemCanResume("file") { false })
        assertTrue(mediaButtonQueueItemCanResume("file") { true })
        assertFalse(mediaButtonQueueItemCanResume("content") { true })
    }
}
