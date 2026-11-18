/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two rows with one stable id crashed the side menu on the Galaxy Tab S9 Ultra (18.11.2026). */
class MusicNavigationTest {
    @Test
    fun everyRowOfTheSideMenuHasItsOwnStableId() {
        val ids = MusicNavigation.adapterIds(MusicNavigation.IDS)
        assertEquals("stable ids must be unique: $ids", ids.size, ids.toSet().size)
    }

    @Test
    fun theOldIdsClashed() {
        // Entries 1…7 with two dividers: the second divider got id 1, like the first entry.
        val ids = MusicNavigation.adapterIds(listOf(1L, 2L, 3L, null, 4L, 5L, null, 6L, 7L))
        assertTrue(ids.toSet().size < ids.size)
    }
}
