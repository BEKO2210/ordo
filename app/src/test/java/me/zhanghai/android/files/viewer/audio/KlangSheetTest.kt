/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class KlangSheetTest {
    @Test
    fun choicesFillBalancedRowsNeverALonelyLastOne() {
        assertEquals(listOf(3, 3), KlangSheet.rowSizes(6, 3)) // speeds, sleep timer
        assertEquals(listOf(3, 2), KlangSheet.rowSizes(5, 3)) // "three and two" as in the Klang sheet
        assertEquals(listOf(4, 3), KlangSheet.rowSizes(7, 4))
        assertEquals(listOf(3, 3), KlangSheet.rowSizes(6, 4))
        assertEquals(listOf(2, 2, 1), KlangSheet.rowSizes(5, 2))
        assertEquals(listOf(3), KlangSheet.rowSizes(3, 3))
        assertEquals(emptyList<Int>(), KlangSheet.rowSizes(0, 3))
    }

    @Test
    fun everyChoiceGetsACell() {
        for (count in 1..12) for (perRow in 1..5) {
            val sizes = KlangSheet.rowSizes(count, perRow)
            assertEquals(count, sizes.sum())
            assert(sizes.all { it in 1..perRow })
            assert(sizes.max() - sizes.min() <= 1)
        }
    }
}
