/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicContentWidthTest {
    @Test
    fun phonesKeepTheirWidth() {
        for (width in listOf(0, 320, 360, 599)) {
            assertEquals(width, musicContentWidthDp(width))
        }
    }

    @Test
    fun mediumWindowsHave34DpGutters() {
        assertEquals(532, musicContentWidthDp(600))
        assertEquals(700, musicContentWidthDp(768))
        assertEquals(771, musicContentWidthDp(839))
    }

    @Test
    fun expandedWindowsLeaveRoomForTwoColumns() {
        assertEquals(730, musicContentWidthDp(840))
        assertEquals(839, musicContentWidthDp(949))
        assertEquals(840, musicContentWidthDp(950))
        assertEquals(914, musicContentWidthDp(1024))
        assertEquals(1090, musicContentWidthDp(1200))
    }

    @Test
    fun wideWindowsStopAt1100Dp() {
        for (width in listOf(1210, 1600, 1920, 2560)) {
            assertEquals(1100, musicContentWidthDp(width))
        }
    }

    @Test
    fun expandedGuttersNeverFallBelow55Dp() {
        for (width in 840..3840) {
            val content = musicContentWidthDp(width)
            assertTrue(content in 0..1100)
            assertTrue(width - content >= 110)
        }
    }
}
