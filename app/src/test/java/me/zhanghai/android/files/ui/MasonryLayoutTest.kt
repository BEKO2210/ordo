/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class MasonryLayoutTest {
    @Test
    fun cardsAlternateAndStackWithoutHoles() {
        // Bass 100, room 120, width 40 (short), reverb 200 (tall), warmth 100.
        val tops = MasonryLayout.columnTops(listOf(100, 120, 40, 200, 100), 2)
        assertArrayEquals(intArrayOf(0, 0), tops[0])
        assertArrayEquals(intArrayOf(100, 0), tops[1])
        // Width starts right below bass, not below the taller room card as a grid row would place it.
        assertArrayEquals(intArrayOf(100, 120), tops[2])
        assertArrayEquals(intArrayOf(140, 120), tops[3])
        assertArrayEquals(intArrayOf(140, 320), tops[4])
        assertArrayEquals(intArrayOf(240, 320), tops[5])
    }

    @Test
    fun oneColumnIsAPlainStack() {
        val tops = MasonryLayout.columnTops(listOf(10, 20, 30), 1)
        assertArrayEquals(intArrayOf(30), tops[2])
        assertArrayEquals(intArrayOf(60), tops[3])
    }
}
