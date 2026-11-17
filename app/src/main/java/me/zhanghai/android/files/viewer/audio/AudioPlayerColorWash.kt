/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import kotlin.math.max
import kotlin.math.pow

internal object AudioPlayerColorWash {
    const val ARTWORK_STRENGTH_PERCENT = 8

    fun averageArgb(pixels: IntArray): Int? {
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        for (color in pixels) {
            if (color ushr 24 < 128) continue
            red += color shr 16 and 0xff
            green += color shr 8 and 0xff
            blue += color and 0xff
            count++
        }
        if (count == 0L) return null
        return 0xff000000.toInt() or
            ((red / count).toInt() shl 16) or
            ((green / count).toInt() shl 8) or
            (blue / count).toInt()
    }

    fun blendWithSurface(surface: Int, artwork: Int, foreground: Int? = null): Int {
        for (strength in ARTWORK_STRENGTH_PERCENT downTo 0) {
            val blended = blend(surface, artwork, strength)
            if (foreground == null || contrast(blended, foreground) >= MINIMUM_TEXT_CONTRAST) {
                return blended
            }
        }
        return surface
    }

    private fun blend(surface: Int, artwork: Int, strength: Int): Int {
        fun channel(shift: Int): Int {
            val surfaceChannel = surface shr shift and 0xff
            val artworkChannel = artwork shr shift and 0xff
            return (surfaceChannel * (100 - strength) + artworkChannel * strength + 50) / 100
        }
        return (surface and -0x1000000) or
            (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun contrast(first: Int, second: Int): Double {
        fun luminance(color: Int): Double {
            fun linear(shift: Int): Double {
                val channel = (color shr shift and 0xff) / 255.0
                return if (channel <= 0.03928) channel / 12.92
                else ((channel + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
        }
        val firstLuminance = luminance(first)
        val secondLuminance = luminance(second)
        return (max(firstLuminance, secondLuminance) + 0.05) /
            (kotlin.math.min(firstLuminance, secondLuminance) + 0.05)
    }

    private const val MINIMUM_TEXT_CONTRAST = 4.5
}
