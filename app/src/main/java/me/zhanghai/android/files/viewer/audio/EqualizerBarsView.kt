/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin
import me.zhanghai.android.files.ui.FrameGrid
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
    private var running = false
    private var playing = false
    private val tick = Runnable { invalidate() }

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
        running = false
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    // The library stays alive under the player; its bars must not keep ticking there.
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) updateAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow) updateAnimation()
    }

    private fun updateAnimation() {
        val run = playing && Motion.any && isAttachedToWindow && isShown && windowVisibility == VISIBLE
        if (run == running) return
        running = run
        removeCallbacks(tick)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (running) {
            // On the shared 30 fps grid instead of every display frame (measured: ~0.6 % of a core per frame per
            // second on the Galaxy Tab S9 Ultra); all bars on screen tick together.
            phase = (SystemClock.uptimeMillis() % CYCLE_MS).toFloat() / CYCLE_MS * (2 * Math.PI).toFloat()
            postDelayed(tick, FrameGrid.delayToNext())
        }
        val size = minOf(width, height) * 0.5f
        val barWidth = size / 5f
        val gap = barWidth
        val left0 = (width - (3 * barWidth + 2 * gap)) / 2f
        val bottom = (height + size) / 2f
        STILL.forEachIndexed { index, still ->
            // Each bar swings on its own beat; at rest they keep the staggered shape.
            val level = if (!running) still else 0.3f + 0.7f * (0.5f + 0.5f * sin(phase * SPEEDS[index]))
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
