/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import me.zhanghai.android.files.player.AudioSettings

/** A preset as a small picture: the ten band gains as one smooth line, 34 × 21 dp. */
class PresetCurveDrawable(density: Float) : Drawable() {
    private val width = (34 * density).toInt()
    private val height = (21 * density).toInt()
    private val inset = 3 * density
    private val gains = FloatArray(AudioSettings.BAND_COUNT)
    private val xs = FloatArray(AudioSettings.BAND_COUNT)
    private val ys = FloatArray(AudioSettings.BAND_COUNT)
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setGains(values: List<Float>) {
        for (i in gains.indices) gains[i] = values.getOrElse(i) { 0f }
        rebuild()
        invalidateSelf()
    }

    fun setColor(color: Int) {
        paint.color = color
        invalidateSelf()
    }

    override fun getIntrinsicWidth(): Int = width

    override fun getIntrinsicHeight(): Int = height

    override fun onBoundsChange(bounds: Rect) {
        rebuild()
    }

    private fun rebuild() {
        val b = bounds
        if (b.isEmpty) return
        val centerY = b.exactCenterY()
        val halfHeight = b.height() / 2f - inset
        for (i in gains.indices) {
            xs[i] = b.left + inset + (b.width() - 2 * inset) * i / (gains.size - 1)
            // The presets stay within about ±7 dB, so a ±8 dB scale makes their shape readable at this size.
            ys[i] = centerY - gains[i].coerceIn(-SCALE_DB, SCALE_DB) / SCALE_DB * halfHeight
        }
        KlangMath.spline(path, xs, ys, gains.size)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val SCALE_DB = 8f
    }
}
