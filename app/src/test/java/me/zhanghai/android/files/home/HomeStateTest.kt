/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeStateTest {
    private val shown = HomeState(isLoading = true, appCount = 140, trashCount = 7, trashSize = 700)
    private val loaded = HomeState(
        categories = HomeCategory.entries.map { CategorySummary(it, 3, 30) },
        isLoading = true, appCount = 149, trashCount = 2, trashSize = 200
    )

    @Test
    fun sectionsEndLoadingAndKeepTheLastCounts() {
        val state = shown.withSections(loaded)
        assertFalse(state.isLoading)
        assertEquals(loaded.categories, state.categories)
        assertEquals(140, state.appCount)
        assertEquals(7, state.trashCount)
        assertEquals(700L, state.trashSize)
    }

    @Test
    fun countsArriveWithoutTouchingTheSections() {
        val sections = shown.withSections(loaded)
        val state = sections.withCounts(loaded)
        assertEquals(sections.copy(appCount = 149, trashCount = 2, trashSize = 200), state)
        assertFalse(state.isLoading)
    }
}
