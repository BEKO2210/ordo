/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.zhanghai.android.files.viewer.video.picture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureAdjustTest {
    private val eps = 1e-5f

    private fun luma(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    @Test
    fun neutralGivesIdentity() {
        val m = PictureAdjust.colorMatrix(PictureAdjustments())
        val identity = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
        assertArrayEquals(identity, m, 0f)
        assertTrue(PictureAdjustments().isNeutral)
    }

    @Test
    fun brightnessAddsToEveryChannel() {
        val m = PictureAdjust.colorMatrix(PictureAdjustments(brightness = 2))
        val out = PictureAdjust.apply(m, 0.2f, 0.4f, 0.6f)
        assertEquals(0.26f, out[0], eps)
        assertEquals(0.46f, out[1], eps)
        assertEquals(0.66f, out[2], eps)
    }

    @Test
    fun contrastKeepsMidGrey() {
        for (step in -3..3) {
            val m = PictureAdjust.colorMatrix(PictureAdjustments(contrast = step))
            val grey = PictureAdjust.apply(m, 0.5f, 0.5f, 0.5f)
            assertEquals(0.5f, grey[0], eps)
            assertEquals(0.5f, grey[2], eps)
            val dark = PictureAdjust.apply(m, 0.25f, 0.25f, 0.25f)
            assertEquals(0.5f - 0.25f * (1f + 0.08f * step), dark[1], eps)
        }
    }

    @Test
    fun saturationKeepsGreyAndLuma() {
        val m = PictureAdjust.colorMatrix(PictureAdjustments(saturation = -3))
        val grey = PictureAdjust.apply(m, 0.3f, 0.3f, 0.3f)
        for (v in grey) assertEquals(0.3f, v, eps)
        val out = PictureAdjust.apply(m, 0.8f, 0.2f, 0.4f)
        assertEquals(luma(floatArrayOf(0.8f, 0.2f, 0.4f)), luma(out), eps)
        val red = PictureAdjust.apply(m, 1f, 0f, 0f)
        assertTrue(red[0] < 1f && red[1] > 0f && red[2] > 0f)
        assertTrue(red[0] > 0.2126f)
    }

    @Test
    fun warmTemperatureRaisesRedLowersBlue() {
        val m = PictureAdjust.colorMatrix(PictureAdjustments(temperature = 1))
        val out = PictureAdjust.apply(m, 0.5f, 0.5f, 0.5f)
        assertTrue(out[0] > 0.5f)
        assertTrue(out[2] < 0.5f)
        assertEquals(0.5f, out[1], eps)
    }

    @Test
    fun brightnessIsAppliedLast() {
        val m = PictureAdjust.colorMatrix(PictureAdjustments(brightness = 1, contrast = 3))
        assertEquals(0.53f, PictureAdjust.apply(m, 0.5f, 0.5f, 0.5f)[0], eps)
    }

    @Test
    fun stepsAreClamped() {
        assertArrayEquals(
            PictureAdjust.colorMatrix(PictureAdjustments(brightness = 3)),
            PictureAdjust.colorMatrix(PictureAdjustments(brightness = 10)),
            0f
        )
    }

    @Test
    fun matrixIsColumnMajor() {
        val m = PictureAdjust.colorMatrix(PictureAdjustments(brightness = 1))
        assertEquals(0.03f, m[12], eps)
        assertEquals(0.03f, m[13], eps)
        assertEquals(0.03f, m[14], eps)
        assertEquals(1f, m[15], eps)
    }

    @Test
    fun sharpnessAndSoften() {
        assertEquals(1.0f, PictureAdjust.sharpness(0.4f, 3), eps)
        assertEquals(0.2f, PictureAdjust.sharpness(0f, 1), eps)
        assertEquals(0.4f, PictureAdjust.sharpness(0.4f, -2), eps)
        assertEquals(0.6f, PictureAdjust.soften(-3), 1e-6f)
        assertEquals(0f, PictureAdjust.soften(2), 0f)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        for (di in listOf(null, true, false)) {
            val a = PictureAdjustments(1, 0, -2, 0, 3, di)
            assertEquals(a, PictureAdjustments.decode(a.encode()))
        }
        assertEquals("1,0,-2,0,3,", PictureAdjustments(1, 0, -2, 0, 3, null).encode())
        assertNull(PictureAdjustments.decode("garbage"))
        assertNull(PictureAdjustments.decode(null))
        assertEquals(3, PictureAdjustments.decode("9,0,0,0,0,")!!.brightness)
    }
}
