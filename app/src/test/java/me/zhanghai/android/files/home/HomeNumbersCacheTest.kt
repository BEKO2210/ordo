/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNumbersCacheTest {
    private val done = HomeState(
        categories = HomeCategory.entries.mapIndexed { i, it -> CategorySummary(it, i + 1L, (i + 1) * 1000L) },
        isLoading = false, appCount = 149, trashCount = 7, trashSize = 181_000_000
    )

    @Test
    fun numbersComeBackStillLoading() {
        val restored = HomeNumbersCache.parse(HomeNumbersCache.format(done))!!
        assertEquals(done.categories, restored.categories)
        assertEquals(149, restored.appCount)
        assertEquals(7, restored.trashCount)
        assertEquals(181_000_000L, restored.trashSize)
        assertTrue(restored.isLoading)
        assertTrue(restored.recentFiles.isEmpty())
    }

    @Test
    fun countsNotYetKnownStayUnknown() {
        val restored = HomeNumbersCache.parse(HomeNumbersCache.format(done.copy(appCount = null, trashCount = null)))!!
        assertNull(restored.appCount)
        assertNull(restored.trashCount)
    }

    @Test
    fun anythingElseIsIgnored() {
        assertNull(HomeNumbersCache.parse(null))
        assertNull(HomeNumbersCache.parse("garbage"))
        assertNull(HomeNumbersCache.parse(HomeNumbersCache.format(done).replaceFirst("1;", "2;")))
        // A category missing (e.g. one added in a later version): the whole cache is skipped.
        assertNull(HomeNumbersCache.parse(HomeNumbersCache.format(done).substringBeforeLast(',')))
    }
}
