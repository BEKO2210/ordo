/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.colorpicker

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.zhanghai.android.files.ui.CheckableView
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.ui.onFirstSight
import kotlin.math.roundToInt

class ColorSwatchView : CheckableView {
    private val density = resources.displayMetrics.density
    private val ringColor = MaterialColors.getColor(
        context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK
    )
    private var checkColor = Color.WHITE
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.5f * density
    }
    private val checkPath = Path()
    private val drawnCheck = Path()
    private val pathMeasure = PathMeasure()
    @ColorInt
    private var color = MaterialColors.getColor(
        context, androidx.appcompat.R.attr.colorPrimary, Color.BLACK
    )
    private var ringAmount = 0f
    private var checkAmount = 0f
    private var checkAlpha = 0f
    private var selectionAnimator: ValueAnimator? = null
    private var applyNextCheckedImmediately = false

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, @AttrRes defStyleAttr: Int) : super(
        context, attrs, defStyleAttr
    )

    constructor(
        context: Context,
        attrs: AttributeSet?,
        @AttrRes defStyleAttr: Int,
        @StyleRes defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    init {
        minimumWidth = (48f * density).roundToInt()
        minimumHeight = (48f * density).roundToInt()
        setWillNotDraw(false)
        foreground = TypedValue().let {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
            if (it.resourceId != 0) context.getDrawable(it.resourceId) else null
        }
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> animatePress(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animatePress(false)
            }
            false
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        )
    }

    fun setColor(@ColorInt color: Int) {
        this.color = color
        checkColor = if (ColorUtils.calculateContrast(Color.WHITE, color) >= 3.0) {
            Color.WHITE
        } else {
            Color.BLACK
        }
        invalidate()
    }

    override fun setChecked(checked: Boolean) {
        val changed = isChecked != checked
        super.setChecked(checked)
        if (applyNextCheckedImmediately) {
            applyNextCheckedImmediately = false
            setSelectionImmediately(checked)
            return
        }
        if (!changed) return
        if (!Motion.any || !isLaidOut) {
            setSelectionImmediately(checked)
        } else {
            animateSelection(checked, 0L)
        }
    }

    fun prepareForBinding() {
        selectionAnimator?.cancel()
        selectionAnimator = null
        animate().cancel()
        // Replaces and cancels a reveal listener left by a previous adapter position.
        onFirstSight {}
        alpha = 1f
        scaleX = 1f
        scaleY = 1f
        applyNextCheckedImmediately = true
        post { applyNextCheckedImmediately = false }
    }

    fun revealSelection(startDelay: Long) {
        if (!isChecked || !Motion.any) return
        setSelectionImmediately(false)
        animateSelection(true, startDelay)
    }

    private fun animateSelection(selected: Boolean, startDelay: Long) {
        selectionAnimator?.cancel()
        val startCheck = if (selected && checkAlpha <= 0f) 0f else checkAmount
        val startAlpha = checkAlpha
        if (selected) ringAmount = 0f
        selectionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (selected) 160 else 100
            this.startDelay = startDelay
            interpolator = if (selected) EMPHASIZED else AccelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                if (selected) {
                    ringAmount = fraction
                    checkAmount = lerp(startCheck, 1f, fraction)
                    checkAlpha = 1f
                } else {
                    ringAmount = 0f
                    checkAmount = startCheck
                    checkAlpha = lerp(startAlpha, 0f, fraction)
                }
                invalidate()
            }
            start()
        }
    }

    fun setSelectionImmediately(selected: Boolean) {
        selectionAnimator?.cancel()
        selectionAnimator = null
        ringAmount = 0f
        checkAmount = if (selected) 1f else 0f
        checkAlpha = if (selected) 1f else 0f
        invalidate()
    }

    private fun animatePress(pressed: Boolean) {
        animate().cancel()
        if (!Motion.any) {
            scaleX = 1f
            scaleY = 1f
            return
        }
        animate()
            .scaleX(if (pressed) 0.96f else 1f)
            .scaleY(if (pressed) 0.96f else 1f)
            .setDuration(if (pressed) 70 else 120)
            .setInterpolator(if (pressed) EMPHASIZED else AccelerateInterpolator())
            .start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = 18f * density
        fillPaint.color = color
        canvas.drawCircle(cx, cy, radius, fillPaint)

        if (ringAmount > 0f) {
            ringPaint.color = ringColor
            ringPaint.alpha = (255 * (1f - ringAmount)).toInt()
            canvas.drawCircle(cx, cy, radius + 3f * density * ringAmount, ringPaint)
        }
        if (checkAlpha <= 0f || checkAmount <= 0f) return
        checkPaint.color = checkColor
        checkPaint.alpha = (255 * checkAlpha).toInt()
        checkPath.reset()
        checkPath.moveTo(cx - 7f * density, cy)
        checkPath.lineTo(cx - 2f * density, cy + 5f * density)
        checkPath.lineTo(cx + 8f * density, cy - 6f * density)
        pathMeasure.setPath(checkPath, false)
        drawnCheck.reset()
        pathMeasure.getSegment(0f, pathMeasure.length * checkAmount, drawnCheck, true)
        canvas.drawPath(drawnCheck, checkPaint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isCheckable = true
        info.isChecked = isChecked
    }

    override fun onDetachedFromWindow() {
        selectionAnimator?.cancel()
        selectionAnimator = null
        animate().cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)

        private fun lerp(start: Float, end: Float, fraction: Float): Float =
            start + (end - start) * fraction
    }
}
