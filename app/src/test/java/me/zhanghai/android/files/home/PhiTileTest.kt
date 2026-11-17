/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import org.junit.Assert.assertEquals
import org.junit.Test

class PhiTileTest {
    @Test
    fun tileIsPhiTimesAsWideAsHigh() {
        assertEquals(71, phiTileHeight(115))
        assertEquals(89, phiTileHeight(144))
        assertEquals(55, phiTileHeight(89))
    }
}
