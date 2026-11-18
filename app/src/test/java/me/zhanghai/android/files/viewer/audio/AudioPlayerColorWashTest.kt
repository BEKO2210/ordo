/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun seedIsTheMostColourfulHueNotTheAverage() {
        // A dark blue cover with an orange sun on a fifth of it: grey average, but the player takes the blue.
        val pixels = IntArray(100) { if (it < 20) 0xffff8800.toInt() else 0xff102a80.toInt() }
        val seed = AudioPlayerColorWash.seed(pixels)!!
        assertEquals(0x10, seed shr 16 and 0xff)
        assertEquals(0x80, seed and 0xff)
    }

    @Test
    fun greyCoverGivesItsAverage() {
        val pixels = IntArray(100) { if (it % 2 == 0) 0xff202020.toInt() else 0xffa0a0a0.toInt() }
        assertEquals(0xff606060.toInt(), AudioPlayerColorWash.seed(pixels))
        assertNull(AudioPlayerColorWash.seed(intArrayOf(0x00ff0000)))
    }

    @Test
    fun schemePassesWcagInLightAndDark() {
        val covers = intArrayOf(0xff102a80.toInt(), 0xffffee00.toInt(), 0xff00ff66.toInt(), 0xffff0055.toInt())
        for (seed in covers) {
            for ((surface, onSurface, light) in listOf(
                Triple(0xfff5f7fa.toInt(), 0xff1a1c1e.toInt(), true),
                Triple(0xff19181a.toInt(), 0xffe6e1e5.toInt(), false)
            )) {
                val scheme = AudioPlayerColorWash.scheme(seed, surface, onSurface, light) ?: continue
                assertTrue(AudioPlayerColorWash.contrastRatio(onSurface, scheme.tint) >= 4.5)
                assertTrue(AudioPlayerColorWash.contrastRatio(scheme.accent, scheme.tint) >= 3.0)
                assertTrue(AudioPlayerColorWash.contrastRatio(scheme.onAccent, scheme.accent) >= 4.5)
            }
        }
    }

    @Test
    fun veilGrowsUntilTextReadsOverEveryPixel() {
        // White text over a cover that is pure white in places needs a strong dark veil; over black it needs none.
        val veil = 0xff19181a.toInt()
        val text = 0xffe6e1e5.toInt()
        val overWhite = AudioPlayerColorWash.veilAlpha(intArrayOf(0xffffffff.toInt()), veil, intArrayOf(text), text, 0.5f)
        val overBlack = AudioPlayerColorWash.veilAlpha(intArrayOf(0xff000000.toInt()), veil, intArrayOf(text), text, 0.5f)
        assertEquals(0.5f, overBlack)
        assertTrue(overWhite > 0.5f)
        val seen = 0xff000000.toInt() or (((0xff * (1 - overWhite) + 0x19 * overWhite).toInt()) * 0x010101)
        assertTrue(AudioPlayerColorWash.contrastRatio(text, seen) >= 4.4)
    }
}
