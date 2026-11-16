/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.HapticFeedbackConstants
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.google.android.material.color.MaterialColors
import me.zhanghai.android.files.R
import me.zhanghai.android.files.player.AudioSettings
import me.zhanghai.android.files.ui.Motion

class EqualizerCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var gainsDb: List<Float> = List(AudioSettings.BAND_COUNT) { 0f }
        set(value) {
            field = value.take(AudioSettings.BAND_COUNT).let {
                it + List(AudioSettings.BAND_COUNT - it.size) { 0f }
            }
            invalidate()
            accessibilityHelper.invalidateRoot()
        }
    var onGainChanged: ((index: Int, gainDb: Float) -> Unit)? = null
    var onDragStateChanged: ((Boolean) -> Unit)? = null
    private var handleScale = 1f
    private var gainAnimator: ValueAnimator? = null

    private val handler = Handler(Looper.getMainLooper())
    private var activeBand = -1
    private var dragArmed = false
    private val armDrag = Runnable {
        dragArmed = activeBand >= 0
        if (dragArmed) {
            parent?.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            animateHandle(1.3f)
            onDragStateChanged?.invoke(true)
        }
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1f) }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(10f); textAlign = Paint.Align.CENTER }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11f); textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val fillPath = Path()
    private val accessibilityHelper = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            if (width == 0 || height == 0) return INVALID_ID
            val left = paddingLeft + dp(16f)
            val right = width - paddingRight - dp(16f)
            return (((x - left) / (right - left)) * (AudioSettings.BAND_COUNT - 1))
                .toInt().coerceIn(0, AudioSettings.BAND_COUNT - 1)
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            repeat(AudioSettings.BAND_COUNT) { virtualViewIds += it }
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat
        ) {
            val label = bandLabel(virtualViewId)
            val value = context.getString(R.string.audio_sound_db, gainsDb[virtualViewId])
            node.contentDescription = "$label, $value"
            node.className = android.widget.SeekBar::class.java.name
            node.isEnabled = isEnabled
            node.isFocusable = true
            node.isScrollable = true
            node.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(
                AccessibilityNodeInfoCompat.RangeInfoCompat.RANGE_TYPE_FLOAT,
                AudioSettings.MIN_GAIN_DB,
                AudioSettings.MAX_GAIN_DB,
                gainsDb[virtualViewId]
            )
            node.addAction(
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD,
                    context.getString(R.string.audio_sound_decrease, label)
                )
            )
            node.addAction(
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD,
                    context.getString(R.string.audio_sound_increase, label)
                )
            )
            val left = paddingLeft + dp(16f)
            val right = width - paddingRight - dp(16f)
            val top = paddingTop + dp(20f)
            val bottom = height - paddingBottom - dp(20f)
            val x = bandToX(virtualViewId, left, right)
            val y = gainToY(gainsDb[virtualViewId], top, bottom)
            val radius = dp(24f).toInt()
            node.setBoundsInParent(Rect(x.toInt() - radius, y.toInt() - radius, x.toInt() + radius, y.toInt() + radius))
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?
        ): Boolean {
            if (!isEnabled) return false
            val delta = when (action) {
                AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD -> -ACCESSIBILITY_STEP_DB
                AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD -> ACCESSIBILITY_STEP_DB
                else -> return false
            }
            setGain(virtualViewId, (gainsDb[virtualViewId] + delta).coerceIn(
                AudioSettings.MIN_GAIN_DB, AudioSettings.MAX_GAIN_DB
            ))
            invalidateVirtualView(virtualViewId)
            sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_SELECTED)
            return true
        }
    }

    init {
        minimumHeight = dp(200f).toInt()
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + dp(30f)
        val right = width - paddingRight - dp(12f)
        val top = paddingTop + dp(28f)
        val bottom = height - paddingBottom - dp(28f)
        val onSurface = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666.toInt()
        )
        val primary = MaterialColors.getColor(
            this, androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4.toInt()
        )
        gridPaint.color = (onSurface and 0x00FFFFFF) or 0x33000000
        curvePaint.color = primary
        handlePaint.color = primary
        handleRingPaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        labelPaint.color = onSurface
        for (gain in listOf(-12f, -6f, 0f, 6f, 12f)) {
            val y = gainToY(gain, top, bottom)
            canvas.drawLine(left, y, right, y, gridPaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(if (gain > 0) "+${gain.toInt()}" else gain.toInt().toString(), left - dp(5f), y + dp(3f), labelPaint)
        }
        path.reset()
        gainsDb.forEachIndexed { index, gain ->
            val x = bandToX(index, left, right)
            val y = gainToY(gain, top, bottom)
            if (index == 0) path.moveTo(x, y) else {
                val previousX = bandToX(index - 1, left, right)
                val previousY = gainToY(gainsDb[index - 1], top, bottom)
                val midpoint = (previousX + x) / 2f
                path.cubicTo(midpoint, previousY, midpoint, y, x, y)
            }
        }
        fillPath.set(path); fillPath.lineTo(right, bottom); fillPath.lineTo(left, bottom); fillPath.close()
        fillPaint.shader = LinearGradient(0f, top, 0f, bottom, primary and 0x47FFFFFF, primary and 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, curvePaint)
        gainsDb.forEachIndexed { index, gain ->
            val x = bandToX(index, left, right); val y = gainToY(gain, top, bottom)
            canvas.drawCircle(x, y, dp(9f) * if (index == activeBand) handleScale else 1f, handlePaint)
            canvas.drawCircle(x, y, dp(9f) * if (index == activeBand) handleScale else 1f, handleRingPaint)
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(shortBandLabel(index), x, height - paddingBottom - dp(5f), labelPaint)
            if (index == activeBand && dragArmed) {
                val text = context.getString(R.string.audio_sound_curve_value, gain, bandLabel(index))
                val half = bubbleTextPaint.measureText(text) / 2f + dp(8f)
                bubblePaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorInverseSurface, Color.DKGRAY)
                bubbleTextPaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorInverseOnSurface, Color.WHITE)
                canvas.drawRoundRect(x - half, y - dp(38f), x + half, y - dp(14f), dp(12f), dp(12f), bubblePaint)
                canvas.drawText(text, x, y - dp(21f), bubbleTextPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val left = paddingLeft + dp(30f)
        val right = width - paddingRight - dp(12f)
        val top = paddingTop + dp(28f)
        val bottom = height - paddingBottom - dp(28f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeBand = (((event.x - left) / (right - left)) * (AudioSettings.BAND_COUNT - 1))
                    .toInt().coerceIn(0, AudioSettings.BAND_COUNT - 1)
                dragArmed = false
                handler.postDelayed(armDrag, HOLD_TO_DRAG_MS)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragArmed) updateGain(event.y, top, bottom)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragArmed) updateGain(event.y, top, bottom) else performClick()
                finishGesture()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                finishGesture()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    private fun updateGain(y: Float, top: Float, bottom: Float) {
        val gain = (AudioSettings.MAX_GAIN_DB -
            (y - top).coerceIn(0f, bottom - top) / (bottom - top) *
            (AudioSettings.MAX_GAIN_DB - AudioSettings.MIN_GAIN_DB))
            .let { (it * 2).toInt() / 2f }
        setGain(activeBand, gain)
    }

    private fun setGain(index: Int, gain: Float) {
        val crossedZero = gainsDb[index] != 0f && gain == 0f
        val updated = gainsDb.toMutableList().apply { this[index] = gain }
        gainsDb = updated
        if (crossedZero) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onGainChanged?.invoke(index, gain)
    }

    private fun finishGesture() {
        handler.removeCallbacks(armDrag)
        parent?.requestDisallowInterceptTouchEvent(false)
        activeBand = -1
        dragArmed = false
        animateHandle(1f)
        onDragStateChanged?.invoke(false)
        invalidate()
    }

    fun setGains(values: List<Float>, animate: Boolean) {
        val target = values.take(AudioSettings.BAND_COUNT).let { it + List(AudioSettings.BAND_COUNT - it.size) { 0f } }
        if (!animate || !Motion.any || !isLaidOut) { gainsDb = target; return }
        val start = gainsDb
        gainAnimator?.cancel()
        gainAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { animator -> gainsDb = List(AudioSettings.BAND_COUNT) { index -> start[index] + (target[index] - start[index]) * animator.animatedFraction } }
            start()
        }
    }

    private fun animateHandle(target: Float) {
        if (!Motion.any) { handleScale = target; invalidate(); return }
        ValueAnimator.ofFloat(handleScale, target).apply { duration = 150; addUpdateListener { handleScale = it.animatedValue as Float; invalidate() }; start() }
    }

    private fun bandToX(index: Int, left: Float, right: Float): Float =
        left + (right - left) * index / (AudioSettings.BAND_COUNT - 1)

    private fun gainToY(gain: Float, top: Float, bottom: Float): Float =
        top + (AudioSettings.MAX_GAIN_DB - gain) /
            (AudioSettings.MAX_GAIN_DB - AudioSettings.MIN_GAIN_DB) * (bottom - top)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun bandLabel(index: Int): String {
        val frequency = AudioSettings.BAND_FREQUENCIES_HZ[index]
        return if (frequency >= 1_000) {
            context.getString(R.string.audio_frequency_khz, frequency / 1_000)
        } else {
            context.getString(R.string.audio_frequency_hz, frequency)
        }
    }

    private fun shortBandLabel(index: Int): String = AudioSettings.BAND_FREQUENCIES_HZ[index].let { if (it >= 1000) "${it / 1000}k" else it.toString() }

    companion object {
        private const val HOLD_TO_DRAG_MS = 150L
        private const val ACCESSIBILITY_STEP_DB = 0.5f
    }
}
