/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.google.android.material.color.MaterialColors
import me.zhanghai.android.files.R
import me.zhanghai.android.files.player.AudioSettings

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

    private val handler = Handler(Looper.getMainLooper())
    private var activeBand = -1
    private var dragArmed = false
    private val armDrag = Runnable {
        dragArmed = activeBand >= 0
        if (dragArmed) parent?.requestDisallowInterceptTouchEvent(true)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1f) }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
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
        minimumHeight = dp(220f).toInt()
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + dp(16f)
        val right = width - paddingRight - dp(16f)
        val top = paddingTop + dp(20f)
        val bottom = height - paddingBottom - dp(20f)
        val onSurface = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666.toInt()
        )
        val primary = MaterialColors.getColor(
            this, androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4.toInt()
        )
        gridPaint.color = (onSurface and 0x00FFFFFF) or 0x33000000
        curvePaint.color = primary
        handlePaint.color = primary
        for (gain in listOf(-12f, -6f, 0f, 6f, 12f)) {
            val y = gainToY(gain, top, bottom)
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        path.reset()
        gainsDb.forEachIndexed { index, gain ->
            val x = bandToX(index, left, right)
            val y = gainToY(gain, top, bottom)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, curvePaint)
        gainsDb.forEachIndexed { index, gain ->
            canvas.drawCircle(
                bandToX(index, left, right),
                gainToY(gain, top, bottom),
                dp(if (index == activeBand && dragArmed) 12f else 10f),
                handlePaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val left = paddingLeft + dp(16f)
        val right = width - paddingRight - dp(16f)
        val top = paddingTop + dp(20f)
        val bottom = height - paddingBottom - dp(20f)
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
        val updated = gainsDb.toMutableList().apply { this[index] = gain }
        gainsDb = updated
        onGainChanged?.invoke(index, gain)
    }

    private fun finishGesture() {
        handler.removeCallbacks(armDrag)
        parent?.requestDisallowInterceptTouchEvent(false)
        activeBand = -1
        dragArmed = false
        invalidate()
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

    companion object {
        private const val HOLD_TO_DRAG_MS = 150L
        private const val ACCESSIBILITY_STEP_DB = 0.5f
    }
}
