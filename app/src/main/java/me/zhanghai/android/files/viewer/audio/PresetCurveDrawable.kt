/* Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later */
package me.zhanghai.android.files.viewer.audio

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import com.google.android.material.color.MaterialColors
import me.zhanghai.android.files.player.AudioSettings

/** Allocation-free-in-draw miniature of the same ten gain values used by a preset. */
class PresetCurveDrawable(context: Context, private val gains: List<Float>) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.resources.displayMetrics.density * 2f; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    init { paint.color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, 0xFF6750A4.toInt()) }
    override fun onBoundsChange(bounds: android.graphics.Rect) {
        path.reset(); val count = gains.size.coerceAtLeast(2)
        gains.forEachIndexed { index, gain ->
            val x = bounds.left + bounds.width() * index / (count - 1f)
            val y = bounds.top + bounds.height() * (AudioSettings.MAX_GAIN_DB - gain) / (AudioSettings.MAX_GAIN_DB - AudioSettings.MIN_GAIN_DB)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
    }
    override fun draw(canvas: Canvas) = canvas.drawPath(path, paint)
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
