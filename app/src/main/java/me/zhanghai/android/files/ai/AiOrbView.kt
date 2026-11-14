/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import me.zhanghai.android.files.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The look of the on-device AI: three soft color lights drifting inside a glass sphere with a turning rim.
 * [thinking] speeds it up and lets it breathe. Static when the system turned animations off.
 */
class AiOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val colors = intArrayOf(
        ContextCompat.getColor(context, R.color.ai_glow_1),
        ContextCompat.getColor(context, R.color.ai_glow_2),
        ContextCompat.getColor(context, R.color.ai_glow_3)
    )
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimMatrix = Matrix()
    private val blobMatrix = Matrix()
    private val clip = android.graphics.Path()
    private var blobShaders: List<RadialGradient> = emptyList()

    private var time = 0f
    private var speed = 1f
    private var targetSpeed = 1f
    private var lastFrame = 0L

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcBounds = android.graphics.RectF()
    private var shownProgress = 0f
    private var pulse = 0f

    /** 0..1 draws a progress ring around the sphere instead of the turning rim; below 0 hides it. */
    var progress: Float = -1f
        set(value) {
            if (field < 0f && value >= 0f) shownProgress = value
            field = value
            if (!animator.isStarted) {
                shownProgress = value
                invalidate()
            }
        }

    /** A short swell and flash, for "done". */
    fun celebrate() {
        pulse = 1f
        if (!animator.isStarted) invalidate()
    }

    var thinking: Boolean = false
        set(value) {
            field = value
            targetSpeed = if (value) 3.2f else 1f
        }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { tick() }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val r = min(w, h) / 2f
        blobShaders = colors.map {
            RadialGradient(0f, 0f, r * 0.62f, intArrayOf(it, it and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        }
        val inner = r * 0.82f
        corePaint.shader = RadialGradient(
            w / 2f - inner * 0.25f, h / 2f - inner * 0.3f, inner * 0.6f,
            intArrayOf(Color.argb(150, 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
        haloPaint.shader = RadialGradient(
            w / 2f, h / 2f, r,
            intArrayOf(colors[0] and 0x00FFFFFF or 0x40000000, colors[0] and 0x00FFFFFF), floatArrayOf(0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        rimPaint.shader = SweepGradient(
            w / 2f, h / 2f, intArrayOf(colors[0], colors[1], colors[2], Color.TRANSPARENT, colors[0]), null
        )
        rimPaint.strokeWidth = r * 0.06f
        trackPaint.strokeWidth = r * 0.07f
        trackPaint.color = colors[1] and 0x00FFFFFF or 0x33000000
        progressPaint.strokeWidth = r * 0.07f
        progressPaint.shader = SweepGradient(
            w / 2f, h / 2f, intArrayOf(colors[0], colors[1], colors[2], colors[0]), null
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ValueAnimator.areAnimatorsEnabled()) {
            lastFrame = 0L
            animator.start()
        }
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!ValueAnimator.areAnimatorsEnabled()) return
        if (isVisible && !animator.isStarted) animator.start() else if (!isVisible) animator.cancel()
    }

    private fun tick() {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceAtMost(0.05f)
        lastFrame = now
        speed += (targetSpeed - speed) * min(1f, dt * 3f)
        time += dt * speed
        if (progress >= 0f) shownProgress += (progress - shownProgress) * min(1f, dt * 4f)
        pulse = (pulse - dt * 1.6f).coerceAtLeast(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        if (r <= 0f || blobShaders.isEmpty()) return
        val swell = 0.14f * sin(pulse * Math.PI.toFloat())
        val breath = swell + if (thinking) 1f + 0.05f * sin(time * 2.4f) else 1f + 0.02f * sin(time * 1.2f)
        canvas.drawCircle(cx, cy, r * breath, haloPaint)
        val inner = r * 0.82f * breath
        canvas.save()
        clip.rewind()
        clip.addCircle(cx, cy, inner, android.graphics.Path.Direction.CW)
        canvas.clipPath(clip)
        canvas.drawColor(colors[1] and 0x00FFFFFF or 0x30000000)
        blobShaders.forEachIndexed { i, shader ->
            val angle = time * (0.9f + i * 0.35f) + i * 2.1f
            val orbit = r * (0.26f + 0.06f * sin(time * 0.7f + i))
            val x = cx + cos(angle) * orbit
            val y = cy + sin(angle * 1.3f) * orbit
            blobMatrix.setTranslate(x, y)
            shader.setLocalMatrix(blobMatrix)
            blobPaint.shader = shader
            canvas.drawCircle(x, y, r * 0.62f, blobPaint)
        }
        canvas.drawCircle(cx - inner * 0.25f, cy - inner * 0.3f, inner * 0.6f, corePaint)
        if (pulse > 0f) {
            canvas.drawColor((255 * pulse * 0.5f).toInt() shl 24 or 0x00FFFFFF, android.graphics.PorterDuff.Mode.SRC_ATOP)
        }
        canvas.restore()
        if (progress >= 0f) {
            // Ring sits just outside the sphere and starts at 12 o'clock.
            val ring = r * 0.82f + trackPaint.strokeWidth * 1.2f
            arcBounds.set(cx - ring, cy - ring, cx + ring, cy + ring)
            canvas.drawCircle(cx, cy, ring, trackPaint)
            rimMatrix.setRotate(-90f, cx, cy)
            progressPaint.shader.setLocalMatrix(rimMatrix)
            canvas.drawArc(arcBounds, -90f, 360f * shownProgress.coerceIn(0f, 1f), false, progressPaint)
            return
        }
        rimMatrix.setRotate(time * 90f, cx, cy)
        rimPaint.shader.setLocalMatrix(rimMatrix)
        canvas.drawCircle(cx, cy, inner, rimPaint)
    }
}
