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
    fun seedIsTheMostVividHueNotTheLargestArea() {
        // The aurora cover: a teal band on a fifth of it over a large dull violet sky; the player takes the teal.
        val pixels = IntArray(100) { if (it < 20) 0xff20c8a0.toInt() else 0xff281860.toInt() }
        assertEquals(0xff20c8a0.toInt(), AudioPlayerColorWash.seed(pixels))
        // Without a vivid part the large area still wins.
        val calm = IntArray(100) { if (it < 20) 0xff606a70.toInt() else 0xff281860.toInt() }
        assertEquals(0xff281860.toInt(), AudioPlayerColorWash.seed(calm))
    }

    @Test
    fun greyCoverKeepsTheThemeColours() {
        val pixels = IntArray(100) { if (it % 2 == 0) 0xff202020.toInt() else 0xffa0a0a0.toInt() }
        assertNull(AudioPlayerColorWash.seed(pixels))
        assertNull(AudioPlayerColorWash.seed(intArrayOf(0x00ff0000)))
    }

    @Test
    fun unplayedTrackStandsThreeToOne() {
        for ((accent, background) in listOf(
            0xff5b5a9a.toInt() to 0xffe8e6f0.toInt(), 0xffc6c2ff.toInt() to 0xff1c1b2a.toInt(),
            0xff006874.toInt() to 0xfff5f7fa.toInt()
        )) {
            val track = AudioPlayerColorWash.inactiveTrack(accent, background)
            assertTrue(AudioPlayerColorWash.contrastRatio(track, background) >= 3.0)
            assertTrue(track ushr 24 < 0xff || AudioPlayerColorWash.contrastRatio(accent, background) < 3.6)
        }
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

    @Test
    fun veilKeepsBothThemesReadableOnLightGreyAndDarkCovers() {
        // Light and grey backdrops from the owner's Pixel (20.11.2026) plus black, white and mid grey.
        val covers = intArrayOf(0xffc8d6ea.toInt(), 0xffe4e4e8.toInt(), 0xff000000.toInt(), 0xffffffff.toInt(),
            0xff777777.toInt(), 0xffb57bee.toInt())
        // Ordo's light and dark surface, onSurface and secondary text (ordo_m3_colors.xml).
        val themes = listOf(
            intArrayOf(0xfffafbfc.toInt(), 0xff0f172a.toInt(), 0xff475569.toInt()),
            intArrayOf(0xff0b1018.toInt(), 0xffe6edf5.toInt(), 0xff9aa8ba.toInt())
        )
        for ((surface, onSurface, secondary) in themes) {
            val alpha = AudioPlayerColorWash.veilAlpha(covers, surface, intArrayOf(onSurface, secondary), onSurface, 0.4f)
            for (cover in covers) {
                val seen = mix(cover, surface, alpha)
                assertTrue(AudioPlayerColorWash.contrastRatio(onSurface, seen) >= 4.4)
                assertTrue(AudioPlayerColorWash.contrastRatio(secondary, seen) >= 4.4)
            }
        }
        // What the expanded dark theme showed: the dark theme's text straight on the uninverted light backdrop.
        assertTrue(AudioPlayerColorWash.contrastRatio(0xffe6edf5.toInt(), 0xffc8d6ea.toInt()) < 1.5)
        assertTrue(AudioPlayerColorWash.contrastRatio(0xffe6edf5.toInt(), 0xffe4e4e8.toInt()) < 1.5)
    }

    @Test
    fun translucentSecondaryTextGetsJustEnoughOpacity() {
        // Material's dynamic secondary text on a light wallpaper surface measured 4.3:1 on the Tab (20.11.2026).
        val surface = 0xfffaf5f7.toInt()
        val faint = 0x8c1d1b1b.toInt()
        assertTrue(AudioPlayerColorWash.contrastRatio(faint, surface) < 4.5)
        val readable = AudioPlayerColorWash.readableText(faint, surface)
        assertTrue(AudioPlayerColorWash.contrastRatio(readable, surface) >= 4.5)
        assertTrue(readable ushr 24 < 0xff)
        assertEquals(readable and 0xffffff, faint and 0xffffff)
        // Already readable: unchanged; dark theme too.
        assertEquals(0xff475569.toInt(), AudioPlayerColorWash.readableText(0xff475569.toInt(), 0xfffafbfc.toInt()))
        val dark = AudioPlayerColorWash.readableText(0x66e6e1e5, 0xff141218.toInt())
        assertTrue(AudioPlayerColorWash.contrastRatio(dark, 0xff141218.toInt()) >= 4.5)
    }

    private fun mix(bottom: Int, top: Int, alpha: Float): Int {
        fun channel(shift: Int) =
            ((bottom shr shift and 0xff) * (1 - alpha) + (top shr shift and 0xff) * alpha).toInt() shl shift
        return -0x1000000 or channel(16) or channel(8) or channel(0)
    }
}
