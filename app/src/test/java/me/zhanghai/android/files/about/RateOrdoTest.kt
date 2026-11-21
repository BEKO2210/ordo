/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.about

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateOrdoTest {
    private val day = 24L * 60 * 60 * 1000
    private val first = 1_000_000_000_000L

    @Test
    fun dueAfterAWeekAndTwentySuccesses() {
        assertTrue(RateOrdo.isDue(first + 7 * day, first, 20, shown = false))
    }

    @Test
    fun notBeforeAWeek() {
        assertFalse(RateOrdo.isDue(first + 7 * day - 1, first, 500, shown = false))
    }

    @Test
    fun notBeforeTwentySuccesses() {
        assertFalse(RateOrdo.isDue(first + 60 * day, first, 19, shown = false))
    }

    @Test
    fun neverAgainOnceShown() {
        assertFalse(RateOrdo.isDue(first + 60 * day, first, 500, shown = true))
    }

    @Test
    fun notWithoutAFirstUse() {
        assertFalse(RateOrdo.isDue(first, 0, 20, shown = false))
    }
}
