/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.theme.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppAccentTest {
    private val accents = listOf("aurora", "blue", "mint", "violet", "amber")

    @Test
    fun sameAsOrdoFollowsOrdosAccent() {
        assertNull(AppAccent.own(AppAccent.FOLLOW, accents))
        assertEquals("violet", AppAccent.effective(AppAccent.FOLLOW, "violet", accents))
        // Nothing stored yet is the default, "Same as Ordo".
        assertEquals("blue", AppAccent.effective(null, "blue", accents))
    }

    @Test
    fun anOwnAccentWinsOverOrdos() {
        assertEquals("amber", AppAccent.own("amber", accents))
        assertEquals("amber", AppAccent.effective("amber", "aurora", accents))
    }

    @Test
    fun anUnknownValueFollowsOrdo() {
        assertNull(AppAccent.own("cyan", accents))
        assertEquals("mint", AppAccent.effective("cyan", "mint", accents))
    }
}
