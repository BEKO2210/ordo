/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.util.getColorByAttr
import com.google.android.material.R as MaterialR

/**
 * Depends on androidx.swiperefreshlayout 1.2.0 internals (end trim 0.8 at the trigger, the rotation formula in
 * setProgressRotation, mProgress being replaceable): re-check this class whenever that dependency is bumped.
 * * Ordo's pull-to-refresh pictogram, drawn in place of the stock spinner. SwipeRefreshLayout keeps driving it through
 * the spinner's setters: the end trim grows to 0.8 exactly at the trigger distance, the rotation keeps growing with
 * the overshoot. A folder traces itself while pulling, fills and flips its arrow when letting go would refresh,
 * turns the arrow into a spinning sync arc while loading and draws a check when done.
 */
class PullPictogram(context: Context) : CircularProgressDrawable(context) {
    private val density = context.resources.displayMetrics.density
    private val surface = context.getColorByAttr(MaterialR.attr.colorSurfaceContainerHigh)
    private val container = context.getColorByAttr(MaterialR.attr.colorPrimaryContainer)
    private val onContainer = context.getColorByAttr(MaterialR.attr.colorOnPrimaryContainer)
    private val primary = context.getColorByAttr(androidx.appcompat.R.attr.colorPrimary)
    private val muted = ColorUtils.setAlphaComponent(
        context.getColorByAttr(MaterialR.attr.colorOnSurfaceVariant), 0x61
    )

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    // Material's folder in a 24-unit box, centred on (12, 12).
    private val folder = Path().apply {
        moveTo(4f, 4f); lineTo(10f, 4f); lineTo(12f, 6f); lineTo(20f, 6f)
        arcTo(RectF(18f, 6f, 22f, 10f), -90f, 90f); lineTo(22f, 18f)
        arcTo(RectF(18f, 16f, 22f, 20f), 0f, 90f); lineTo(4f, 20f)
        arcTo(RectF(2f, 16f, 6f, 20f), 90f, 90f); lineTo(2f, 6f)
        arcTo(RectF(2f, 4f, 6f, 8f), 180f, 90f); close()
    }
    private val folderMeasure = PathMeasure(folder, true)
    private val trace = Path()
    private val glyph = Path()
    private val arcBox = RectF(8.6f, 9.6f, 15.4f, 16.4f)

    /** 0 to 1 over the second half of the pull; 1 is the trigger distance. */
    private var pull = 0f
    /** How far past the trigger the finger went, 0 to about 0.5. */
    private var stretch = 0f
    private var alphaValue = 255
    private var armed = false
    private var running = false
    private var done = false

    // Animated values: 0 to 1 for the filled "release now" state, the check stroke and the pop.
    private var armAmount = 0f
    private var checkAmount = 0f
    private var pop = 0f
    private var spin = 0f
    private var armAnimator: ValueAnimator? = null
    private val spinAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { spin = it.animatedValue as Float; invalidateSelf() }
    }

    override fun setStartEndTrim(start: Float, end: Float) {
        if (running || done) return
        pull = (end / MAX_TRIM).coerceIn(0f, 1f)
        setArmed(pull >= 0.999f)
        invalidateSelf()
    }

    override fun setProgressRotation(rotation: Float) {
        // SwipeRefreshLayout: rotation = (-0.25 + 0.4 * pull + 2 * tension) / 2, so above 0.075 is the overshoot.
        stretch = (rotation - 0.075f).coerceIn(0f, 0.5f)
        invalidateSelf()
    }

    override fun setArrowEnabled(show: Boolean) {}

    override fun setArrowScale(scale: Float) {}

    override fun setColorSchemeColors(vararg colors: Int) {}

    // SwipeRefreshLayout fades the stock spinner in and out around the trigger and reads the value back.
    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
    }

    override fun getAlpha(): Int = alphaValue

    override fun isRunning(): Boolean = running

    override fun start() {
        running = true
        stretch = 0f
        setArmed(true)
        if (Motion.any) spinAnimator.start()
        invalidateSelf()
    }

    override fun stop() {
        spinAnimator.cancel()
        armAnimator?.cancel()
        running = false
        done = false
        armed = false
        pull = 0f
        stretch = 0f
        armAmount = 0f
        checkAmount = 0f
        pop = 0f
        invalidateSelf()
    }

    /** Spin turns into a check; [then] runs once it has been seen. A second call while the check shows does nothing. */
    fun finish(then: Runnable) {
        if (done) return
        running = false
        done = true
        spinAnimator.cancel()
        armAmount = 1f
        if (!Motion.any) {
            checkAmount = 1f
            invalidateSelf()
            (callback as? View)?.postDelayed(then, CHECK_HOLD_MS) ?: then.run()
            return
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                checkAmount = it.animatedValue as Float
                pop = popCurve(it.animatedFraction)
                invalidateSelf()
            }
            start()
        }
        (callback as? View)?.postDelayed(then, 300 + CHECK_HOLD_MS) ?: then.run()
    }

    private fun setArmed(value: Boolean) {
        if (armed == value) return
        armed = value
        if (value && !running) {
            (callback as? View)?.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                else HapticFeedbackConstants.CLOCK_TICK
            )
        }
        armAnimator?.cancel()
        val target = if (value) 1f else 0f
        if (!Motion.any) {
            armAmount = target
            return
        }
        armAnimator = ValueAnimator.ofFloat(armAmount, target).apply {
            // Expressive fast spatial spring as a curve: overshoots, then settles.
            duration = 350
            interpolator = PathInterpolator(0.42f, 1.67f, 0.21f, 0.9f)
            addUpdateListener {
                armAmount = (it.animatedValue as Float)
                pop = if (value) popCurve(it.animatedFraction) else 0f
                invalidateSelf()
            }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val radius = minOf(bounds.width(), bounds.height()) / 2f
        val fillAmount = armAmount.coerceIn(0f, 1f)

        // Container: grows from the centre into the primary container once letting go would refresh.
        fill.color = surface
        canvas.drawCircle(cx, cy, radius, fill)
        if (fillAmount > 0f) {
            fill.color = container
            canvas.drawCircle(cx, cy, radius * fillAmount, fill)
        }

        // Pictogram: 24 units at 1.3 dp each, tilted a little by the overshoot.
        val unit = 1.3f * density
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(stretch * 16f)
        val scale = 1f + 0.12f * pop
        canvas.scale(unit * scale, unit * scale)
        canvas.translate(-12f, -12.5f)
        stroke.strokeWidth = 1.7f

        if (fillAmount < 1f) {
            // Outline of the folder, then the part of it the pull has already traced.
            stroke.color = muted
            canvas.drawPath(folder, stroke)
            trace.reset()
            folderMeasure.getSegment(0f, folderMeasure.length * pull, trace, true)
            stroke.color = primary
            canvas.drawPath(trace, stroke)
        }
        if (fillAmount > 0f) {
            fill.color = ColorUtils.setAlphaComponent(onContainer, (255 * fillAmount).toInt())
            canvas.drawPath(folder, fill)
        }

        // Inside the folder: an arrow that sinks with the pull and flips up when armed, the sync arc, the check.
        val glyphColor = ColorUtils.blendARGB(primary, container, fillAmount)
        stroke.color = glyphColor
        stroke.strokeWidth = 1.9f
        when {
            done -> drawCheck(canvas)
            running -> drawSync(canvas)
            else -> drawArrow(canvas, fillAmount)
        }
        canvas.restore()
    }

    private fun drawArrow(canvas: Canvas, flip: Float) {
        canvas.save()
        canvas.translate(12f, 13f + 1.5f * pull * (1f - flip))
        canvas.rotate(180f * flip)
        glyph.reset()
        glyph.moveTo(0f, -3.2f); glyph.lineTo(0f, 3.2f)
        glyph.moveTo(-2.8f, 0.6f); glyph.lineTo(0f, 3.4f); glyph.lineTo(2.8f, 0.6f)
        canvas.drawPath(glyph, stroke)
        canvas.restore()
    }

    private fun drawSync(canvas: Canvas) {
        val start = spin * 360f - 90f
        glyph.reset()
        glyph.addArc(arcBox, start, 280f)
        canvas.drawPath(glyph, stroke)
    }

    private fun drawCheck(canvas: Canvas) {
        glyph.reset()
        glyph.moveTo(8.6f, 13.2f); glyph.lineTo(11f, 15.6f); glyph.lineTo(15.6f, 10.6f)
        val measure = PathMeasure(glyph, false)
        val drawn = Path()
        measure.getSegment(0f, measure.length * checkAmount, drawn, true)
        canvas.drawPath(drawn, stroke)
    }

    companion object {
        private const val MAX_TRIM = 0.8f
        private const val CHECK_HOLD_MS = 350L

        /** 0 → 1 → 0 bump for the scale pop. */
        private fun popCurve(fraction: Float): Float =
            if (fraction < 0.4f) fraction / 0.4f else (1f - fraction) / 0.6f
    }
}
