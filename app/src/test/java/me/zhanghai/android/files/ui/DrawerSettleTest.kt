/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerSettleTest {
    private val parentWidth = 1080
    private val drawerWidth = 840

    @Test
    fun aHalfOpenStartDrawerSlidesEitherWay() {
        assertTrue(drawerHasToSlide(-400, -400 + drawerWidth, parentWidth, open = true))
        assertTrue(drawerHasToSlide(-400, -400 + drawerWidth, parentWidth, open = false))
    }

    // The cases in which DrawerLayout would start no animation and lose track of the drawer.
    @Test
    fun aDrawerDraggedAllTheWayHasNothingLeftToSlide() {
        assertFalse(drawerHasToSlide(0, drawerWidth, parentWidth, open = true))
        assertFalse(drawerHasToSlide(-drawerWidth, 0, parentWidth, open = false))
    }

    @Test
    fun theSameHoldsForADrawerAtTheEnd() {
        assertFalse(drawerHasToSlide(parentWidth - drawerWidth, parentWidth, parentWidth, open = true))
        assertFalse(drawerHasToSlide(parentWidth, parentWidth + drawerWidth, parentWidth, open = false))
        assertTrue(drawerHasToSlide(parentWidth - 100, parentWidth - 100 + drawerWidth, parentWidth, open = true))
    }

    @Test
    fun anOpenDrawerSlidesToCloseAndAClosedOneToOpen() {
        assertTrue(drawerHasToSlide(0, drawerWidth, parentWidth, open = false))
        assertTrue(drawerHasToSlide(-drawerWidth, 0, parentWidth, open = true))
    }
}
