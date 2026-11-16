/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import me.zhanghai.android.files.analysis.StorageInsights
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The home card shows the same percentage as the analysis screen, also at rounding boundaries. */
class StoragePercentLabelTest {
    private fun label(used: Long, total: Long, shown: Float = 1f) = storagePercentLabel(used, total, shown, Locale.US)

    @Test
    fun boundariesMatchTheAnalysis() {
        val total = 100_000L
        // 66.49, 66.5, 66.55, 99.96, 0 and 100 % of the storage.
        for (used in listOf(66_490L, 66_500L, 66_550L, 99_960L, 0L, 100_000L)) {
            assertEquals("used=$used", StorageInsights.share(used, total, Locale.US), label(used, total))
        }
        assertEquals("66%", label(66_490, total))
        assertEquals("67%", label(66_550, total))
        assertEquals(">99%", label(99_960, total))
        assertEquals("0%", label(0, total))
        assertEquals("100%", label(100_000, total))
    }

    @Test
    fun emptyStorageSaysZero() {
        assertEquals("0%", label(0, 0))
        assertEquals("0%", label(5, 0, 0.5f))
    }

    @Test
    fun countingUpUsesTheSameWording() {
        assertEquals("0%", label(66_550, 100_000, 0f))
        assertEquals("33%", label(66_000, 100_000, 0.5f))
        assertEquals("7.3%", label(7_300, 100_000, 0.999999f))
    }
}
