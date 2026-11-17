/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.color.MaterialColors
import me.zhanghai.android.files.R
import me.zhanghai.android.files.ui.Motion

/**
 * The search, drawn: a TV in a soft disc with three rings slowly widening and fading, like a signal going out.
 * One cycle takes 2.4 s. With reduced motion the rings stand still. [found] calms the rings once a device is there.
 */
class CastSearchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val color = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2
        this.color = this@CastSearchView.color
    }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@CastSearchView.color
        alpha = 0x24
    }
    private val icon = AppCompatResources.getDrawable(context, R.drawable.cast_tv_icon_24dp)!!.mutate().apply {
        setTint(this@CastSearchView.color)
    }
    private var phase = 0f
    private var animator: ValueAnimator? = null

    var found = false
        set(value) {
            field = value
            invalidate()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!Motion.any) {
            phase = 0.35f
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
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

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val disc = resources.displayMetrics.density * 26
        val maxRadius = minOf(cx, cy) - ringPaint.strokeWidth
        for (ring in 0 until RINGS) {
            val t = (phase + ring.toFloat() / RINGS) % 1f
            val radius = disc + (maxRadius - disc) * t
            // Fades in quickly, out slowly; quieter once something was found.
            val strength = (if (t < 0.15f) t / 0.15f else (1f - t) / 0.85f) * if (found) 0.35f else 0.7f
            ringPaint.alpha = (strength * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, radius, ringPaint)
        }
        canvas.drawCircle(cx, cy, disc, discPaint)
        val half = (resources.displayMetrics.density * 14).toInt()
        icon.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
        icon.draw(canvas)
    }

    companion object {
        private const val RINGS = 3
        private const val CYCLE_MS = 2_400L
    }
}
