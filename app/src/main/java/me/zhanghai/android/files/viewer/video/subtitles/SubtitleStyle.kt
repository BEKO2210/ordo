/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.subtitles

/**
 * The look of subtitles in steps (chips, never a slider): size, what sits behind the text, its colour. The default is
 * the box (BBC: white on black), the only one that keeps WCAG 2.2 1.4.3 (contrast ≥ 4.5:1) on any picture: its alpha
 * is [boxAlpha], checked against white and every other background in SubtitleLogicTest. "None" and "Shadow" depend
 * on the picture.
 */
data class SubtitleStyle(
    val size: Size = Size.M,
    val background: Background = Background.BOX,
    val color: Color = Color.WHITE
) {
    /** Factor on Media3's default text size (5.33 % of the picture height). */
    enum class Size(val factor: Float) { S(0.8f), M(1f), L(1.25f), XL(1.55f) }

    enum class Background { NONE, SHADOW, BOX }

    enum class Color(val argb: Int) { WHITE(0xFFFFFFFF.toInt()), YELLOW(0xFFFFEB3B.toInt()) }

    fun encode(): String = "${size.name},${background.name},${color.name}"

    companion object {
        const val MIN_CONTRAST = 4.5
        /** The box is at least this dark (BBC/YouTube-like), more if a text colour needed it for 4.5:1. */
        const val BOX_MIN_ALPHA = 0.75

        /** WCAG 2.2 relative luminance of an sRGB colour (alpha ignored). */
        fun luminance(rgb: Int): Double {
            fun channel(value: Int): Double {
                val c = value / 255.0
                return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(rgb shr 16 and 0xFF) + 0.7152 * channel(rgb shr 8 and 0xFF) +
                0.0722 * channel(rgb and 0xFF)
        }

        /** WCAG 2.2 contrast ratio, 1 to 21. */
        fun contrast(a: Int, b: Int): Double {
            val (light, dark) = luminance(a).let { la -> luminance(b).let { lb -> maxOf(la, lb) to minOf(la, lb) } }
            return (light + 0.05) / (dark + 0.05)
        }

        /** A black box of [alpha] over [background], as the eye sees it. */
        fun overBackground(alpha: Double, background: Int): Int {
            fun mix(shift: Int) = ((background shr shift and 0xFF) * (1 - alpha)).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }

        /** The lowest box alpha (in 0.05 steps, at least [BOX_MIN_ALPHA]) with ≥ 4.5:1 for [text] over white. */
        fun boxAlpha(text: Int): Double {
            var alpha = BOX_MIN_ALPHA
            while (alpha < 1.0 && contrast(text, overBackground(alpha, 0xFFFFFFFF.toInt())) < MIN_CONTRAST) {
                alpha += 0.05
            }
            return alpha.coerceAtMost(1.0)
        }

        /** Anything unreadable falls back to the default per part. */
        fun decode(value: String?): SubtitleStyle {
            val parts = value?.split(',').orEmpty()
            val default = SubtitleStyle()
            return SubtitleStyle(
                Size.values().firstOrNull { it.name == parts.getOrNull(0) } ?: default.size,
                Background.values().firstOrNull { it.name == parts.getOrNull(1) } ?: default.background,
                Color.values().firstOrNull { it.name == parts.getOrNull(2) } ?: default.color
            )
        }
    }
}
