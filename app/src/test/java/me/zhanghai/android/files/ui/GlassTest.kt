/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassTest {
    @Test
    fun barIsSolidAtRestAndGlassOnceContentIsUnderIt() {
        assertEquals("a bar with nothing under it must not show through", 0xFF, glassOverlayAlpha(0f))
        assertEquals(0x99, glassOverlayAlpha(1f))
        val steps = (0..10).map { glassOverlayAlpha(it / 10f) }
        assertTrue("the glass fades in without a jump back", steps.zipWithNext().all { (a, b) -> b <= a })
    }

    @Test
    fun amountOutsideTheRangeIsClamped() {
        assertEquals(0xFF, glassOverlayAlpha(-1f))
        assertEquals(0x99, glassOverlayAlpha(3f))
    }
}
