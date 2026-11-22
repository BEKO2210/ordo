/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

class SelectionMorePopupTest {
    // Galaxy S21 at 560 dpi, landscape: 1080×2400 px → about 309 dp tall.
    private val s21Zoom560LandscapeH = 309
    private val barAndNav = 80
    private val item = 48
    private val overflowItems = 7

    @Test
    fun aTallMenuOnAShortLandscapeScreenStopsAtTheBarAndScrolls() {
        val windowTop = 24
        val barTop = s21Zoom560LandscapeH - barAndNav
        val content = overflowItems * item
        val height = min(content, SelectionMorePopup.maxHeight(windowTop, barTop))
        val popupBottom = barTop
        val popupTop = popupBottom - height
        assertTrue(SelectionMorePopup.sitsAboveBar(popupBottom, barTop))
        assertTrue(popupTop >= windowTop)
        assertTrue("menu is taller than the space above the bar", height < content)
    }

    @Test
    fun overlappingTheBarFailsTheCheckTheDeviceTestUses() {
        val barTop = 229
        assertFalse(SelectionMorePopup.sitsAboveBar(barTop + 20, barTop))
        assertTrue(SelectionMorePopup.sitsAboveBar(barTop, barTop))
    }

    @Test
    fun showXPutsTheEndCornerOnTheMoreButton() {
        val barLeft = 0
        val barWidth = 686
        val buttonLeft = 560
        val buttonRight = 662
        assertEquals(buttonRight - barLeft, SelectionMorePopup.showX(barLeft, buttonLeft, buttonRight, rtl = false))
        // MenuPopupHelper subtracts the bar width when gravity is RIGHT.
        assertEquals(buttonRight - (barLeft + barWidth), SelectionMorePopup.showX(barLeft, buttonLeft, buttonRight, rtl = false) - barWidth)
        assertEquals(buttonLeft - barLeft, SelectionMorePopup.showX(barLeft, buttonLeft, buttonRight, rtl = true))
    }

    @Test
    fun aPhonePortraitMenuThatFitsIsStillAboveTheBar() {
        val windowTop = 24
        val barTop = 780
        val content = overflowItems * item
        val height = min(content, SelectionMorePopup.maxHeight(windowTop, barTop))
        assertEquals(content, height)
        assertTrue(SelectionMorePopup.sitsAboveBar(barTop, barTop))
    }
}
