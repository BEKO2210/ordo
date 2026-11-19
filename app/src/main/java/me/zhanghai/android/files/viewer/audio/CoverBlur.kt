/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

/**
 * The player's atmosphere is the cover itself, shrunk to [SIZE] px and blurred once per song on a background thread
 * (the idea of Metrolist's liquid background, rebuilt for Views). Three box blurs in a row come close to a Gaussian, as
 * StackBlur does, and stay cheap on every API level; nothing is blurred per frame.
 */
internal object CoverBlur {
    const val SIZE = 128
    const val RADIUS = 14

    /** Blurs [pixels] (a [width] × [height] ARGB image) in place and lifts its saturation by [saturation]. */
    fun blur(pixels: IntArray, width: Int, height: Int, radius: Int = RADIUS, saturation: Float = 1.35f) {
        val temp = IntArray(pixels.size)
        repeat(3) {
            boxPass(pixels, temp, width, height, radius, horizontal = true)
            boxPass(temp, pixels, width, height, radius, horizontal = false)
        }
        if (saturation != 1f) for (i in pixels.indices) pixels[i] = saturate(pixels[i], saturation)
    }

    private fun boxPass(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val window = radius * 2 + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val clamped = i.coerceIn(0, length - 1)
                return if (horizontal) line * width + clamped else clamped * width + line
            }
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val c = src[at(i)]
                r += c shr 16 and 0xff
                g += c shr 8 and 0xff
                b += c and 0xff
            }
            for (i in 0 until length) {
                dst[at(i)] = -0x1000000 or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val leaving = src[at(i - radius)]
                val entering = src[at(i + radius + 1)]
                r += (entering shr 16 and 0xff) - (leaving shr 16 and 0xff)
                g += (entering shr 8 and 0xff) - (leaving shr 8 and 0xff)
                b += (entering and 0xff) - (leaving and 0xff)
            }
        }
    }

    private fun saturate(color: Int, amount: Float): Int {
        val r = color shr 16 and 0xff
        val g = color shr 8 and 0xff
        val b = color and 0xff
        val grey = 0.2126f * r + 0.7152f * g + 0.0722f * b
        fun channel(c: Int) = (grey + (c - grey) * amount).toInt().coerceIn(0, 255)
        return (color and -0x1000000) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
}
