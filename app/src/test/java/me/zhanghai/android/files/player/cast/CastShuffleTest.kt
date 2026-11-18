/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.cast

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastShuffleTest {
    @Test
    fun readsThePhonesShuffleOrderFromItsTimeline() {
        // A shuffled timeline over five songs: 3 → 0 → 4 → 1 → 2, then the end (-1).
        val next = mapOf(3 to 0, 0 to 4, 4 to 1, 1 to 2, 2 to -1)
        assertArrayEquals(intArrayOf(3, 0, 4, 1, 2), CastPlayback.traversal(3) { next.getValue(it) })
        assertArrayEquals(intArrayOf(), CastPlayback.traversal(-1) { it })
    }

    @Test
    fun theTvPlaysTheSameNextSongsAndHandsTheOrderBack() {
        val queue = listOf("a", "b", "c", "d", "e")
        val order = intArrayOf(3, 0, 4, 1, 2)
        assertTrue(CastPlayback.isPermutation(order, queue.size))
        // What CastPlayer gives Media3: the queue in play order, so "next" walks the phone's shuffle.
        val playOrder = order.map { queue[it] }
        assertEquals(listOf("d", "a", "e", "b", "c"), playOrder)
        // Casting started on "a": the next song on the TV is "e", as on the phone.
        assertEquals("e", playOrder[playOrder.indexOf("a") + 1])
        // Handing back: the same order in queue indices, the current song at its queue index.
        assertArrayEquals(order, playOrder.map { queue.indexOf(it) }.toIntArray())
        assertEquals(4, queue.indexOf(playOrder[2]))
    }

    @Test
    fun aBrokenOrderIsNotUsed() {
        assertFalse(CastPlayback.isPermutation(intArrayOf(0, 1, 1), 3))
        assertFalse(CastPlayback.isPermutation(intArrayOf(0, 1), 3))
    }

    @Test
    fun aNewShuffleStartsWithTheCurrentSongAndKeepsEveryOther() {
        val order = CastPlayback.shuffledStartingWith(6, 4, Random(7))
        assertEquals(4, order[0])
        assertTrue(CastPlayback.isPermutation(order, 6))
    }
}
