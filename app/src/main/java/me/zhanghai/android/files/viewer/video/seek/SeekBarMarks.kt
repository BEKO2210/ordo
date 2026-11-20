/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.seek

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import com.google.android.material.slider.Slider

/**
 * Chapter ticks and the A-B stretch drawn over the seek bar (its ViewOverlay, so the slider's layout and touch stay
 * untouched). Positions follow the slider's track, mirrored for right-to-left.
 */
internal class SeekBarMarks(private val slider: Slider, accentColor: Int) : Drawable() {
    var chapterStartsMs: LongArray = LongArray(0)
        set(value) { field = value; invalidateSelf() }
    var ab = SeekLogic.AbRepeat()
        set(value) { field = value; invalidateSelf() }
    var durationMs = 0L
        set(value) { if (field != value) { field = value; invalidateSelf() } }

    private val density = slider.resources.displayMetrics.density
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
    // A black edge keeps a white tick visible on a light frame.
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val abPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor; alpha = 0x55 }

    init {
        slider.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            setBounds(0, 0, view.width, view.height)
        }
        slider.overlay.add(this)
    }

    private fun x(timeMs: Long): Float {
        val fraction = (timeMs.toFloat() / durationMs).coerceIn(0f, 1f)
        val side = slider.trackSidePadding.toFloat()
        val width = slider.trackWidth.toFloat()
        val rtl = slider.layoutDirection == View.LAYOUT_DIRECTION_RTL
        return side + width * (if (rtl) 1 - fraction else fraction)
    }

    override fun draw(canvas: Canvas) {
        if (durationMs <= 0) return
        val cy = bounds.height() / 2f
        val half = 2 * density
        val tickHalfHeight = 7 * density
        if (ab.hasA) {
            val a = x(ab.aMs)
            if (ab.isLooping) {
                val b = x(ab.bMs)
                canvas.drawRect(minOf(a, b), cy - 4 * density, maxOf(a, b), cy + 4 * density, bandPaint)
                canvas.drawRoundRect(b - half, cy - 10 * density, b + half, cy + 10 * density, half, half, abPaint)
            }
            canvas.drawRoundRect(a - half, cy - 10 * density, a + half, cy + 10 * density, half, half, abPaint)
        }
        for (start in chapterStartsMs) {
            if (start <= 0) continue
            val cx = x(start)
            canvas.drawRect(cx - half, cy - tickHalfHeight - density, cx + half, cy + tickHalfHeight + density, edgePaint)
            canvas.drawRect(cx - half / 2, cy - tickHalfHeight, cx + half / 2, cy + tickHalfHeight, tickPaint)
        }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
