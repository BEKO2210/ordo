/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLayoutTest {
    @Test fun homeCategoryColumns_phoneIsThree() {
        assertEquals(3, homeCategoryColumns(360))
        assertEquals(3, homeCategoryColumns(599))
    }

    @Test fun homeCategoryColumns_tabletSteps() {
        assertEquals(3, homeCategoryColumns(600))
        assertEquals(3, homeCategoryColumns(839))
        assertEquals(9, homeCategoryColumns(840))
        assertEquals(9, homeCategoryColumns(924))
        assertEquals(9, homeCategoryColumns(1480))
    }

    @Test fun homeCategoryColumns_nineTilesAlwaysFullRows() {
        for (width in listOf(360, 600, 839, 840, 1200)) {
            val columns = homeCategoryColumns(width)
            assertEquals(0, HOME_CATEGORY_TILE_COUNT % columns)
        }
    }

    @Test fun homeRecentColumns_phoneUnchanged() {
        assertEquals(4, homeRecentColumns(360))
        assertEquals(4, homeRecentColumns(599))
    }

    @Test fun homeRecentColumns_widerShowsMoreColumns() {
        assertEquals(6, homeRecentColumns(600))
        assertEquals(8, homeRecentColumns(840))
    }

    @Test fun homeQuickAccessColumns() {
        assertEquals(3, homeQuickAccessColumns(400))
        assertEquals(5, homeQuickAccessColumns(600))
        assertEquals(8, homeQuickAccessColumns(840))
    }

    @Test fun homeStorageColumns() {
        assertEquals(1, homeStorageColumns(1000, 1))
        assertEquals(1, homeStorageColumns(800, 5))
        assertEquals(2, homeStorageColumns(840, 2))
        assertEquals(2, homeStorageColumns(1200, 3))
    }

    @Test
    fun narrowTilesShowTheSizeAlone() {
        // Galaxy Tab S9 Ultra: 924 dp in portrait (92 dp tiles), 1480 dp in landscape (153 dp tiles).
        assertEquals(true, homeCategoryCompact(924))
        assertEquals(false, homeCategoryCompact(1480))
        // Phones keep three wide tiles with count and size.
        assertEquals(false, homeCategoryCompact(411))
    }

    @Test
    fun glassAtTheTopFollowsTheScrolledDistance() {
        assertEquals(0f, homeHeaderGlassAmount(false, 0, 1000, 60f))
        assertEquals(0.5f, homeHeaderGlassAmount(false, 30, 1000, 60f))
        assertEquals(1f, homeHeaderGlassAmount(false, 400, 1000, 60f))
    }

    @Test
    fun glassAtTheBottomFollowsThePageLeftBelow() {
        // Page not scrolled: content lies under the bar, so it is glass from the start.
        assertEquals(1f, homeHeaderGlassAmount(true, 0, 1000, 60f))
        assertEquals(0.5f, homeHeaderGlassAmount(true, 970, 1000, 60f))
        // Scrolled to the end: the last row sits above the bar, the bar is flat.
        assertEquals(0f, homeHeaderGlassAmount(true, 1000, 1000, 60f))
        // A page shorter than the screen never reaches under the bar.
        assertEquals(0f, homeHeaderGlassAmount(true, 0, -200, 60f))
    }
}
