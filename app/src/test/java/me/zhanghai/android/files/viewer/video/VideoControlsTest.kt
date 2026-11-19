/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoControlsTest {
    @Test
    fun tapZonesAre28PercentEachSide() {
        assertEquals(-1, tapZone(280f, 1000, rtl = false))
        assertEquals(0, tapZone(281f, 1000, rtl = false))
        assertEquals(0, tapZone(719f, 1000, rtl = false))
        assertEquals(1, tapZone(720f, 1000, rtl = false))
        assertEquals(1, tapZone(100f, 1000, rtl = true))
        assertEquals(0, tapZone(100f, 0, rtl = false))
    }

    @Test
    fun touchesInTheSystemGestureAreasAreNotGestures() {
        // 1000 x 2000 px, 60 px side gesture insets, 80 px bottom, 70 px status bar.
        fun system(x: Float, y: Float) = isInGestureArea(x, y, 1000, 2000, 60, 70, 60, 80)
        assertTrue(system(10f, 1000f))
        assertTrue(system(950f, 1000f))
        assertTrue(system(500f, 1950f))
        // 5 % of 2000 px is more than the status bar here.
        assertTrue(system(500f, 99f))
        assertFalse(system(500f, 100f))
        assertFalse(system(60f, 1000f))
        assertFalse(system(939f, 1919f))
    }
}
