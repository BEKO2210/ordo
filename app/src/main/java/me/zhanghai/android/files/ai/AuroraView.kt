/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import me.zhanghai.android.files.R
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * A slow northern-lights backdrop: three large soft lights in the Aurora colours drifting across the
 * screen. Decorative only; still when the system turned animations off.
 */
class AuroraView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
    private val colors = intArrayOf(
        ContextCompat.getColor(context, R.color.ai_glow_1),
        ContextCompat.getColor(context, R.color.ai_glow_2),
        ContextCompat.getColor(context, R.color.ai_glow_3)
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var shaders: List<RadialGradient> = emptyList()
    private var radius = 0f
    private var time = 0f
    private var lastFrame = 0L

    /** Moves the lights a little, e.g. by page, so the backdrop follows the content. */
    var shift: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val now = System.nanoTime()
            if (lastFrame != 0L) time += ((now - lastFrame) / 1e9f).coerceAtMost(0.05f)
            lastFrame = now
            invalidate()
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        radius = max(w, h) * 0.55f
        // Low alpha keeps text on top readable in both themes.
        shaders = colors.map {
            RadialGradient(0f, 0f, radius, intArrayOf(it and 0x00FFFFFF or 0x5A000000, it and 0x00FFFFFF),
                null, Shader.TileMode.CLAMP)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ValueAnimator.areAnimatorsEnabled()) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        shaders.forEachIndexed { i, shader ->
            val t = time * (0.07f + i * 0.03f) + i * 2.2f
            val x = w * (0.5f + 0.42f * cos(t) - shift * 0.15f * (i - 1))
            val y = h * (0.32f + 0.22f * sin(t * 1.3f) + i * 0.12f)
            matrix.setTranslate(x, y)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            canvas.drawCircle(x, y, radius, paint)
        }
    }
}
