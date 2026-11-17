/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

/**
 * The cover of an album, artist or folder without one: a calm gradient picked from the name, its initials and a small
 * glyph, so a library without covers is no wall of identical grey tiles. The same name always looks the same.
 */
class MusicPlaceholderDrawable(context: Context, name: String?, @DrawableRes glyphRes: Int) : Drawable() {
    private val colors = PALETTE[MusicLibrary.paletteIndex(name, PALETTE.size)]
    private val initials = MusicLibrary.initials(name)
    private val glyph = ContextCompat.getDrawable(context, glyphRes)!!.mutate().apply { setTint(GLYPH_COLOR) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    override fun onBoundsChange(bounds: Rect) {
        fill.shader = LinearGradient(
            bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
            colors.first.toInt(), colors.second.toInt(), Shader.TileMode.CLAMP
        )
        val size = minOf(bounds.width(), bounds.height())
        text.textSize = size * 0.36f
        // Without initials the glyph is the picture; with them it sits small in the lower end corner.
        val glyphSize = (size * if (initials.isEmpty()) 0.42f else 0.22f).toInt()
        val inset = if (initials.isEmpty()) (size - glyphSize) / 2 else (size * 0.1f).toInt()
        val left = if (initials.isEmpty()) bounds.centerX() - glyphSize / 2 else bounds.right - inset - glyphSize
        val top = if (initials.isEmpty()) bounds.centerY() - glyphSize / 2 else bounds.bottom - inset - glyphSize
        glyph.setBounds(left, top, left + glyphSize, top + glyphSize)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, fill)
        if (initials.isNotEmpty()) {
            val y = bounds.exactCenterY() - (text.descent() + text.ascent()) / 2
            canvas.drawText(initials, bounds.exactCenterX(), y, text)
        }
        glyph.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        text.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    companion object {
        private const val GLYPH_COLOR = 0xB3FFFFFF.toInt()

        // Muted pairs that carry white text (≥ 4.5:1 on the darker end) in the light and the dark theme alike.
        private val PALETTE = listOf(
            0xFF5B7DB1 to 0xFF34496E, 0xFF5E9484 to 0xFF355B50, 0xFFB07A5B to 0xFF6E4532,
            0xFF8C6BB1 to 0xFF52397A, 0xFFB0697F to 0xFF703D4F, 0xFF6E8F5A to 0xFF435A34,
            0xFF5F8FA8 to 0xFF36596E, 0xFFA08A55 to 0xFF655430
        )
    }
}
