/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverBlurTest {
    @Test
    fun flatColourStaysFlat() {
        val pixels = IntArray(16 * 16) { 0xff336699.toInt() }
        CoverBlur.blur(pixels, 16, 16, radius = 3, saturation = 1f)
        assertTrue(pixels.all { it == 0xff336699.toInt() })
    }

    @Test
    fun hardEdgeBecomesASoftRamp() {
        val width = 32
        val pixels = IntArray(width * 8) { if (it % width < width / 2) 0xff000000.toInt() else 0xffffffff.toInt() }
        CoverBlur.blur(pixels, width, 8, radius = 3, saturation = 1f)
        val row = (0 until width).map { pixels[it] and 0xff }
        assertEquals(0, row.first())
        assertEquals(255, row.last())
        // Monotonic, and the two pixels at the edge are no longer black and white.
        assertTrue(row.zipWithNext().all { (a, b) -> a <= b })
        assertTrue(row[width / 2 - 1] in 40..215 && row[width / 2] in 40..215)
    }
}
