/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.subtitles

/**
 * The look of subtitles, for every film. Steps only (−/+ and segments, never a slider). The default follows the BBC
 * subtitle guidelines (8.1 "white on black", 9.2.1 line height 7–8 % of the picture on phones, 3.3 two lines) and mpv's
 * defaults (sans-serif, outline): white, medium weight, a black box of [boxAlpha], which keeps WCAG 2.2 1.4.3
 * (contrast ≥ 4.5:1) on any picture (checked against white in SubtitleLogicTest). Outline and shadow need no box and
 * stay readable on light and dark pictures by their black edge; "no edge at all" is not offered any more.
 */
data class SubtitleStyle(
    /** Percent of Ordo's base size ([baseFraction]), [SIZE_MIN]..[SIZE_MAX] in steps of [SIZE_STEP]. */
    val sizePercent: Int = 100,
    val font: Font = Font.MEDIUM,
    val background: Background = Background.BOX,
    val color: Color = Color.WHITE,
    /** Distance of the last line from the picture's bottom, percent of its height ([BOTTOM_MIN]..[BOTTOM_MAX]). */
    val bottomPercent: Int = BOTTOM_DEFAULT,
    /** ASS/SSA: the file's own colours, fonts and sizes instead of Ordo's look. */
    val fileStyles: Boolean = false,
    /** Android's caption settings (Settings › Accessibility › Captions) instead of Ordo's look. */
    val system: Boolean = false
) {
    /** Android's font families: no bundled font, so the APK stays small and every script has its glyphs. */
    enum class Font(val family: String) {
        STANDARD("sans-serif"), MEDIUM("sans-serif-medium"), CONDENSED("sans-serif-condensed"), SERIF("serif")
    }

    enum class Background { OUTLINE, SHADOW, BOX }

    /** Caption colours of TV and streaming players; all light, so the black edge or box always carries them. */
    enum class Color(val argb: Int) {
        WHITE(0xFFFFFFFF.toInt()), YELLOW(0xFFFFEB3B.toInt()), CYAN(0xFF80DEEA.toInt()), GREEN(0xFFB9F6CA.toInt())
    }

    fun stepSize(direction: Int): SubtitleStyle =
        copy(sizePercent = (sizePercent + direction * SIZE_STEP).coerceIn(SIZE_MIN, SIZE_MAX))

    fun stepBottom(direction: Int): SubtitleStyle =
        copy(bottomPercent = (bottomPercent + direction * BOTTOM_STEP).coerceIn(BOTTOM_MIN, BOTTOM_MAX))

    /** Text size as a fraction of the picture's height. */
    fun textFraction(phone: Boolean): Float = baseFraction(phone) * sizePercent / 100f

    fun encode(): String = listOf(
        VERSION, sizePercent, font.name, background.name, color.name, bottomPercent, fileStyles, system
    ).joinToString(",")

    companion object {
        const val SIZE_MIN = 60
        const val SIZE_MAX = 200
        const val SIZE_STEP = 10
        const val BOTTOM_MIN = 2
        const val BOTTOM_MAX = 30
        const val BOTTOM_STEP = 2
        /** Media3's own bottom padding (SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION). */
        const val BOTTOM_DEFAULT = 8
        private const val VERSION = "v2"

        /**
         * Media3's 5.33 % of the picture height gives a line of about 6.2 %. BBC 9.2.1/9.2.2: on a phone the full
         * authoring size (line 7–8 %: 6.4 % text), on bigger screens 0.6–0.8 of it (5.33 % is 0.71).
         */
        fun baseFraction(phone: Boolean): Float = if (phone) 0.064f else 0.0533f

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

        /** Anything unreadable falls back to the default per part; the 0.16 form ("XL,NONE,YELLOW") is carried over. */
        fun decode(value: String?): SubtitleStyle {
            val parts = value?.split(',').orEmpty()
            val default = SubtitleStyle()
            if (parts.firstOrNull() != VERSION) {
                val size = mapOf("S" to 80, "M" to 100, "L" to 130, "XL" to 160)[parts.getOrNull(0)]
                return default.copy(
                    sizePercent = size ?: default.sizePercent,
                    // "None" is gone: plain text vanished on light pictures. Its closest readable form is the outline.
                    background = when (parts.getOrNull(1)) {
                        "NONE" -> Background.OUTLINE
                        "SHADOW" -> Background.SHADOW
                        else -> default.background
                    },
                    color = Color.values().firstOrNull { it.name == parts.getOrNull(2) } ?: default.color
                )
            }
            fun int(index: Int, min: Int, max: Int, fallback: Int) =
                parts.getOrNull(index)?.toIntOrNull()?.takeIf { it in min..max } ?: fallback
            return SubtitleStyle(
                int(1, SIZE_MIN, SIZE_MAX, default.sizePercent),
                Font.values().firstOrNull { it.name == parts.getOrNull(2) } ?: default.font,
                Background.values().firstOrNull { it.name == parts.getOrNull(3) } ?: default.background,
                Color.values().firstOrNull { it.name == parts.getOrNull(4) } ?: default.color,
                int(5, BOTTOM_MIN, BOTTOM_MAX, default.bottomPercent),
                parts.getOrNull(6) == "true",
                parts.getOrNull(7) == "true"
            )
        }
    }
}
