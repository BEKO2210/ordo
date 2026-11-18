/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

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

    fun blendWithSurface(
        surface: Int, artwork: Int, foreground: Int? = null, maxStrength: Int = ARTWORK_STRENGTH_PERCENT
    ): Int {
        for (strength in maxStrength downTo 0) {
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

    /** The player's colours for one cover; every pair that is drawn on each other passes WCAG (see [scheme]). */
    data class Scheme(
        val accent: Int,
        val onAccent: Int,
        val container: Int,
        val onContainer: Int,
        /** The surface tinted towards the cover: the veil over the atmosphere and the base of text contrast. */
        val tint: Int
    )

    /**
     * The cover's leading colour: the hue with the most colour in it (each pixel weighted by its chroma, neighbouring
     * hues counted half, chroma squared so vivid beats large and dull), averaged over that hue's pixels. A cover with
     * hardly any colour gives null: the player keeps the theme's accent instead of a grey one or a stray pixel's.
     */
    fun seed(pixels: IntArray): Int? {
        val weight = DoubleArray(HUE_BINS)
        val red = DoubleArray(HUE_BINS)
        val green = DoubleArray(HUE_BINS)
        val blue = DoubleArray(HUE_BINS)
        var opaque = 0
        for (color in pixels) {
            if (color ushr 24 < 128) continue
            opaque++
            val r = color shr 16 and 0xff
            val g = color shr 8 and 0xff
            val b = color and 0xff
            val high = maxOf(r, g, b)
            val chroma = high - minOf(r, g, b)
            if (chroma < MIN_CHROMA) continue
            val sector = when (high) {
                r -> ((g - b).toDouble() / chroma).mod(6.0)
                g -> (b - r).toDouble() / chroma + 2
                else -> (r - g).toDouble() / chroma + 4
            }
            val bin = (sector / 6 * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
            // Squared: a vivid band (the aurora's teal) outweighs a large, dull area (its violet sky).
            val w = (chroma / 255.0) * (chroma / 255.0)
            weight[bin] += w
            red[bin] += r * w
            green[bin] += g * w
            blue[bin] += b * w
        }
        if (opaque == 0) return null
        var best = -1
        var bestScore = 0.0
        for (i in 0 until HUE_BINS) {
            val score = weight[i] + (weight[(i + HUE_BINS - 1) % HUE_BINS] + weight[(i + 1) % HUE_BINS]) / 2
            if (score > bestScore) {
                best = i
                bestScore = score
            }
        }
        if (best < 0 || bestScore < opaque * MIN_COLOURFUL_SHARE) return null
        var w = 0.0
        var r = 0.0
        var g = 0.0
        var b = 0.0
        for (offset in -1..1) {
            val i = (best + offset + HUE_BINS) % HUE_BINS
            w += weight[i]
            r += red[i]
            g += green[i]
            b += blue[i]
        }
        return 0xff000000.toInt() or ((r / w).roundToInt() shl 16) or ((g / w).roundToInt() shl 8) or
            (b / w).roundToInt()
    }

    /**
     * Material colour roles from [seed] (MaterialColors.getColorRoles works on every API level), with the surface
     * tinted [TINT_STRENGTH_PERCENT] towards the container colour. Null when the roles would break WCAG: text on the
     * tint under 4.5:1, the accent (controls) on the tint under 3:1, or the icon on the accent under 4.5:1. The player
     * then keeps the theme's colours.
     */
    fun scheme(seed: Int, surface: Int, onSurface: Int, isLight: Boolean): Scheme? {
        val roles = MaterialColors.getColorRoles(seed, isLight)
        val tint = blendWithSurface(surface, roles.accentContainer, onSurface, TINT_STRENGTH_PERCENT)
        if (contrast(onSurface, tint) < MINIMUM_TEXT_CONTRAST ||
            contrast(roles.accent, tint) < MINIMUM_CONTROL_CONTRAST ||
            contrast(roles.onAccent, roles.accent) < MINIMUM_TEXT_CONTRAST) {
            return null
        }
        return Scheme(roles.accent, roles.onAccent, roles.accentContainer, roles.onAccentContainer, tint)
    }

    /**
     * How opaque the [veil] over the blurred cover must at least be, from [minimum] up in small steps, so that every
     * text colour in [texts] keeps 4.5:1 and [control] keeps 3:1 against every pixel seen through it. Text colours
     * with alpha are composited first, as the screen draws them.
     */
    fun veilAlpha(pixels: IntArray, veil: Int, texts: IntArray, control: Int, minimum: Float): Float {
        var percent = (minimum * 100).toInt()
        while (percent < 100) {
            val ok = pixels.all { pixel ->
                val background = blend(pixel or -0x1000000, veil, percent)
                texts.all { contrast(over(it, background), background) >= MINIMUM_TEXT_CONTRAST } &&
                    contrast(control, background) >= MINIMUM_CONTROL_CONTRAST
            }
            if (ok) return percent / 100f
            percent += VEIL_STEP_PERCENT
        }
        return 1f
    }

    /**
     * The seek bar's unplayed track: [accent] at the lowest opacity (from 30 %) that still stands 3:1 against
     * [background] with a margin (WCAG 1.4.11 asks 3:1; the track shows how long the song is). Review panel 18.11.2026: a pale container
     * colour vanished on light and grey covers.
     */
    fun inactiveTrack(accent: Int, background: Int): Int {
        for (percent in 30..100 step 5) {
            val color = (percent * 255 / 100 shl 24) or (accent and 0xffffff)
            if (contrastRatio(color, background) >= TRACK_CONTRAST) return color
        }
        return accent
    }

    fun contrastRatio(first: Int, second: Int): Double = contrast(over(first, second), second)

    private fun over(top: Int, bottom: Int): Int {
        val alpha = top ushr 24
        if (alpha == 0xff) return top
        return blend(bottom or -0x1000000, top or -0x1000000, (alpha * 100 + 127) / 255)
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
    private const val MINIMUM_CONTROL_CONTRAST = 3.0
    // On the Tab's screen 3:1 against the veil's colour measured only 2.7–3.0:1 and 3.5:1 still 2.97:1 (the blurred
    // cover shows through the veil, the line is anti-aliased); 4:1 leaves that margin.
    private const val TRACK_CONTRAST = 4.0
    private const val TINT_STRENGTH_PERCENT = 14
    private const val VEIL_STEP_PERCENT = 2
    private const val HUE_BINS = 36
    private const val MIN_CHROMA = 24
    private const val MIN_COLOURFUL_SHARE = 0.01
}
