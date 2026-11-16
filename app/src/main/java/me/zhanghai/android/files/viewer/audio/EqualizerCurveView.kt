/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
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

    init {
        minimumHeight = dp(220f).toInt()
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
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

    private fun updateGain(y: Float, top: Float, bottom: Float) {
        val gain = (AudioSettings.MAX_GAIN_DB -
            (y - top).coerceIn(0f, bottom - top) / (bottom - top) *
            (AudioSettings.MAX_GAIN_DB - AudioSettings.MIN_GAIN_DB))
            .let { (it * 2).toInt() / 2f }
        val updated = gainsDb.toMutableList().apply { this[activeBand] = gain }
        gainsDb = updated
        onGainChanged?.invoke(activeBand, gain)
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

    companion object {
        private const val HOLD_TO_DRAG_MS = 150L
    }
}
