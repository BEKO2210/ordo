/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which images leave Coil for the tiled view, and how large an unknown image may be decoded. */
class LargeImageDecisionTest {
    @Test
    fun squareOf30000OverflowsIntButStillTiles() {
        // 30000² × 4 wraps a 32-bit int to a negative number, which used to look smaller than the limit.
        assertTrue(30000 * 30000 * 4 < 0)
        assertTrue(shouldUseLargeImageView(30000, 30000, "image/jpeg"))
    }

    @Test
    fun squareJustPastIntOverflowStillTiles() {
        assertTrue(23171 * 23171 * 4 < 0)
        assertTrue(shouldUseLargeImageView(23171, 23171, "image/jpeg"))
    }

    @Test
    fun squareJustUnderIntOverflowStillTiles() {
        assertTrue(23170 * 23170 * 4 > 0)
        assertTrue(shouldUseLargeImageView(23170, 23170, "image/jpeg"))
    }

    @Test
    fun smallJpegAndGifStayInCoil() {
        assertFalse(shouldUseLargeImageView(64, 64, "image/jpeg"))
        assertFalse(shouldUseLargeImageView(30000, 30000, "image/gif"))
        assertFalse(shouldUseLargeImageView(0, 0, "image/jpeg"))
        assertFalse(shouldUseLargeImageView(-1, 100, "image/png"))
    }

    @Test
    fun unknownSizeAndSvgAreCapped() {
        assertEquals(UNKNOWN_IMAGE_EDGE_CAP_PX, coilEdgeCapPx(0, 0, "image/jpeg"))
        assertEquals(UNKNOWN_IMAGE_EDGE_CAP_PX, coilEdgeCapPx(-1, 8000, "image/png"))
        assertEquals(4096, coilEdgeCapPx(12000, 12000, "image/svg+xml"))
        assertNull(coilEdgeCapPx(800, 600, "image/jpeg"))
        assertNull(coilEdgeCapPx(30000, 30000, "image/gif"))
    }
}
