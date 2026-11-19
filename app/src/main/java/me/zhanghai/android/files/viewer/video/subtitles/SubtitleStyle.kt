/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.subtitles

/** The look of subtitles in steps (chips, never a slider): size, what sits behind the text, its colour. */
data class SubtitleStyle(
    val size: Size = Size.M,
    val background: Background = Background.SHADOW,
    val color: Color = Color.WHITE
) {
    /** Factor on Media3's default text size (5.33 % of the picture height). */
    enum class Size(val factor: Float) { S(0.8f), M(1f), L(1.25f), XL(1.55f) }

    enum class Background { NONE, SHADOW, BOX }

    enum class Color(val argb: Int) { WHITE(0xFFFFFFFF.toInt()), YELLOW(0xFFFFEB3B.toInt()) }

    fun encode(): String = "${size.name},${background.name},${color.name}"

    companion object {
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
