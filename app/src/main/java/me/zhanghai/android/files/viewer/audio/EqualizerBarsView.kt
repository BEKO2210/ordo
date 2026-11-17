/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin
import me.zhanghai.android.files.ui.Motion

/**
 * Three bars that mark the song that is playing. They move calmly while it plays; paused, or without motion (Ordo's
 * setting or the system's), they stand still at fixed heights.
 */
class EqualizerBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var phase = 0f
    private var animator: ValueAnimator? = null
    private var playing = false

    var color: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidate()
        }

    fun setPlaying(playing: Boolean) {
        this.playing = playing
        updateAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow) updateAnimation()
    }

    private fun updateAnimation() {
        val run = playing && Motion.any && isAttachedToWindow && isShown
        if (!run) {
            animator?.cancel()
            animator = null
            phase = 0f
            invalidate()
            return
        }
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height) * 0.5f
        val barWidth = size / 5f
        val gap = barWidth
        val left0 = (width - (3 * barWidth + 2 * gap)) / 2f
        val bottom = (height + size) / 2f
        STILL.forEachIndexed { index, still ->
            // Each bar swings on its own beat; at rest they keep the staggered shape.
            val level = if (animator == null) still else 0.3f + 0.7f * (0.5f + 0.5f * sin(phase * SPEEDS[index]))
            val left = left0 + index * (barWidth + gap)
            rect.set(left, bottom - size * level, left + barWidth, bottom)
            canvas.drawRoundRect(rect, barWidth / 2, barWidth / 2, paint)
        }
    }

    companion object {
        private const val CYCLE_MS = 2_400L
        private val STILL = floatArrayOf(0.55f, 1f, 0.75f)
        private val SPEEDS = floatArrayOf(2f, 3f, 5f)
    }
}
