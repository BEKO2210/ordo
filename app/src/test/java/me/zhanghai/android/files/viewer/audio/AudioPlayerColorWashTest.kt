/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioPlayerColorWashTest {
    @Test
    fun averageIgnoresTransparentPixels() {
        assertEquals(
            0xff7f4020.toInt(),
            AudioPlayerColorWash.averageArgb(
                intArrayOf(0xff000000.toInt(), 0xffff8040.toInt(), 0x00123456)
            )
        )
        assertNull(AudioPlayerColorWash.averageArgb(intArrayOf(0x00123456)))
    }

    @Test
    fun artworkIsOnlyEightPercentOfSurfaceWash() {
        assertEquals(
            0xffeae4e2.toInt(),
            AudioPlayerColorWash.blendWithSurface(0xfff5f1ed.toInt(), 0xff705060.toInt())
        )
        assertEquals(
            0xff1d2023.toInt(),
            AudioPlayerColorWash.blendWithSurface(0xff16191c.toInt(), 0xff6e6e70.toInt())
        )
    }

    @Test
    fun washBacksOffBeforeTextContrastDropsBelowFourPointFive() {
        val unrestricted = AudioPlayerColorWash.blendWithSurface(0xff777777.toInt(), 0xff000000.toInt())
        val contrastSafe = AudioPlayerColorWash.blendWithSurface(
            0xff777777.toInt(), 0xff000000.toInt(), 0xff000000.toInt()
        )
        assertEquals(0xff6d6d6d.toInt(), unrestricted)
        assertEquals(0xff757575.toInt(), contrastSafe)
    }
}
