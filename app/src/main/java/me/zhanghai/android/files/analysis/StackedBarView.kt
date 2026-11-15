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
import me.zhanghai.android.files.ui.onFirstSight

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

    // Only one grow or sweep runs at a time: a rebind while animating must not leave two animators on this bar.
    private var animator: ValueAnimator? = null
    private var shine = -1f
    private val visibleRect = android.graphics.Rect()

    // While on screen, the light passes over the bar again every few seconds: the bar looks alive, not frozen.
    private val nextSweep = Runnable {
        if (windowVisibility == VISIBLE && isShown && getGlobalVisibleRect(visibleRect)) sweep() else scheduleSweep()
    }

    private fun scheduleSweep() {
        removeCallbacks(nextSweep)
        if (gradient != null && me.zhanghai.android.files.ui.Motion.full) postDelayed(nextSweep, SWEEP_PAUSE_MILLIS)
    }

    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Called with the grow progress from 0 to 1, e.g. to count a percent label up with the bar. */
    var onProgress: ((Float) -> Unit)? = null

    /** Optional start and end colour drawn across the whole bar instead of the parts' own colours. */
    var gradient: IntArray? = null
        set(value) {
            field = value
            paint.shader = null
            invalidate()
        }

    /** [parts] are colour and value; [max] is the full width (e.g. used space), at least their sum. */
    fun setParts(parts: List<Pair<Int, Long>>, max: Long) {
        val filtered = parts.filter { it.second > 0 }
        val newMax = maxOf(max, filtered.sumOf { it.second }, 1)
        // Rebinding the same numbers (e.g. while a list scrolls) must not replay the animation.
        if (filtered == this.parts && newMax == this.max) return
        this.parts = filtered
        this.max = newMax
        animator?.cancel()
        removeCallbacks(nextSweep)
        shine = -1f
        if (me.zhanghai.android.files.ui.Motion.any) {
            setProgress(0f)
            // Grow in when scrolled into view, not while still below the fold.
            onFirstSight {
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1100
                    startDelay = 150
                    interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
                    addUpdateListener { setProgress(it.animatedValue as Float) }
                    doOnEnd { if (gradient != null && me.zhanghai.android.files.ui.Motion.full) sweep() }
                }
                animator?.start()
            }
        } else {
            setProgress(1f)
        }
    }

    /** Empties the bar and waits: the next [replay] grows it in. Used while its screen is still covered. */
    fun hold() {
        animator?.cancel()
        removeCallbacks(nextSweep)
        shine = -1f
        if (me.zhanghai.android.files.ui.Motion.any) setProgress(0f)
    }

    /** Grows the bar in again with the same numbers, e.g. after pull to refresh. */
    fun replay() {
        val parts = parts
        val max = max
        this.parts = emptyList()
        this.max = 0
        setParts(parts, max)
    }

    private fun setProgress(value: Float) {
        progress = value
        onProgress?.invoke(value)
        invalidate()
    }

    // One pass of light over the filled part once it has grown in: polished, not busy.
    private fun sweep() {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { shine = it.animatedValue as Float; invalidate() }
            doOnEnd {
                shine = -1f
                invalidate()
                scheduleSweep()
            }
        }
        animator?.start()
    }

    // The gradient spans the bar's width, so a new width needs a new gradient.
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        paint.shader = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(nextSweep)
        // Leave the bar filled rather than half drawn when its view comes back.
        if (animator?.isRunning == true) {
            animator?.cancel()
            shine = -1f
            setProgress(1f)
        }
    }

    // Only a natural end counts: a cancelled grow must not start the sweep.
    private fun ValueAnimator.doOnEnd(action: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
        private var cancelled = false

        override fun onAnimationCancel(animation: android.animation.Animator) {
            cancelled = true
        }

        override fun onAnimationEnd(animation: android.animation.Animator) {
            if (!cancelled) action()
        }
    })

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
                gradient?.let { colors ->
                    if (paint.shader == null) {
                        paint.shader = android.graphics.LinearGradient(0f, 0f, w, 0f, colors, null,
                            android.graphics.Shader.TileMode.CLAMP)
                    }
                }
                rect.set(x, 0f, x + width - if (i < parts.lastIndex) gap else 0f, h)
                val corner = if (parts.size == 1) h / 2 else h / 4
                canvas.drawRoundRect(rect, corner, corner, paint)
            }
            x += full
        }
        val filled = parts.sumOf { it.second } * w / max * minOf(1f, progress * 1.15f)
        if (shine in 0f..1f && filled > 0) {
            val band = 0.35f * filled
            val center = -band + (filled + 2 * band) * shine
            shinePaint.shader = android.graphics.LinearGradient(center - band, 0f, center + band, 0f,
                intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF), null, android.graphics.Shader.TileMode.CLAMP)
            rect.set(0f, 0f, filled, h)
            canvas.drawRoundRect(rect, h / 2, h / 2, shinePaint)
        }
        canvas.restore()
        // A soft light at the leading edge of a single gradient bar.
        val colors = gradient
        if (colors != null && parts.size == 1 && filled > h) {
            glowPaint.shader = android.graphics.RadialGradient(filled - h / 2, h / 2, h * 1.6f,
                intArrayOf(colors.last() and 0x00FFFFFF or 0x88000000.toInt(), colors.last() and 0x00FFFFFF), null,
                android.graphics.Shader.TileMode.CLAMP)
            canvas.drawCircle(filled - h / 2, h / 2, h * 1.6f, glowPaint)
        }
    }

    private companion object {
        const val SWEEP_PAUSE_MILLIS = 4_500L
    }
}
