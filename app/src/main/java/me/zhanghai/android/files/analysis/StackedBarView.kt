/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.analysis

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import me.zhanghai.android.files.R

/** One rounded bar split into coloured parts with 2 dp gaps; the parts grow in from the start one after another. */
class StackedBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.chart_track) }
    private val clip = Path()
    private val rect = RectF()
    private val gap = 2 * resources.displayMetrics.density
    private var parts: List<Pair<Int, Long>> = emptyList()
    private var max = 0L
    private var progress = 1f

    /** [parts] are colour and value; [max] is the full width (e.g. used space), at least their sum. */
    fun setParts(parts: List<Pair<Int, Long>>, max: Long) {
        this.parts = parts.filter { it.second > 0 }
        this.max = maxOf(max, this.parts.sumOf { it.second }, 1)
        if (ValueAnimator.areAnimatorsEnabled()) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100
                startDelay = 150
                interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
                addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            }.start()
        } else {
            progress = 1f
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2, h / 2, track)
        clip.rewind()
        clip.addRoundRect(rect, h / 2, h / 2, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        var x = 0f
        parts.forEachIndexed { i, (color, value) ->
            // Each part starts when the one before it is a third done: a soft cascade.
            val local = ((progress * (parts.size + 2) - i) / 3f).coerceIn(0f, 1f)
            val full = w * value / max
            val width = full * local
            if (width > 0.5f) {
                paint.color = color
                rect.set(x, 0f, x + width - if (i < parts.lastIndex) gap else 0f, h)
                canvas.drawRoundRect(rect, h / 4, h / 4, paint)
            }
            x += full
        }
        canvas.restore()
    }
}
