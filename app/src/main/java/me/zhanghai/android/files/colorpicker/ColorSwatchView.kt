/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.colorpicker

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import com.google.android.material.R as MaterialR
import me.zhanghai.android.files.ui.CheckableView
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.util.getColorByAttr
import kotlin.math.roundToInt

class ColorSwatchView : CheckableView {
    private val density = resources.displayMetrics.density
    private val ringColor = context.getColorByAttr(MaterialR.attr.colorOnSurface)
    private val checkColor = context.getColorByAttr(MaterialR.attr.colorOnPrimary)
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
    @ColorInt
    private var color = context.getColorByAttr(androidx.appcompat.R.attr.colorPrimary)
    private var ringAmount = 0f
    private var checkAmount = 0f
    private var checkAlpha = 0f
    private var selectionAnimator: ValueAnimator? = null

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
        invalidate()
    }

    override fun setChecked(checked: Boolean) {
        if (isChecked == checked) return
        super.setChecked(checked)
        if (!Motion.any || !isLaidOut) {
            setSelectionImmediately(if (checked) 1f else 0f)
        } else {
            animateSelection(checked, 0L)
        }
    }

    fun revealSelection(startDelay: Long) {
        if (!isChecked || !Motion.any) return
        setSelectionImmediately(0f)
        animateSelection(true, startDelay)
    }

    private fun animateSelection(selected: Boolean, startDelay: Long) {
        selectionAnimator?.cancel()
        val startRing = ringAmount
        val startCheck = checkAmount
        val startAlpha = checkAlpha
        val target = if (selected) 1f else 0f
        selectionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (selected) 200 else 150
            this.startDelay = startDelay
            interpolator = if (selected) EMPHASIZED else AccelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                ringAmount = lerp(startRing, target, fraction)
                if (selected) {
                    checkAmount = lerp(startCheck, 1f, fraction)
                    checkAlpha = 1f
                } else {
                    checkAmount = startCheck
                    checkAlpha = lerp(startAlpha, 0f, fraction)
                }
                invalidate()
            }
            start()
        }
    }

    private fun setSelectionImmediately(amount: Float) {
        selectionAnimator?.cancel()
        ringAmount = amount
        checkAmount = amount
        checkAlpha = amount
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
            .scaleX(if (pressed) 0.92f else 1f)
            .scaleY(if (pressed) 0.92f else 1f)
            .setDuration(if (pressed) 150 else 220)
            .setInterpolator(EMPHASIZED)
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
            ringPaint.alpha = (255 * ringAmount).toInt()
            canvas.drawCircle(cx, cy, radius + 3f * density * ringAmount, ringPaint)
        }
        if (checkAlpha <= 0f || checkAmount <= 0f) return
        checkPaint.color = checkColor
        checkPaint.alpha = (255 * checkAlpha).toInt()
        checkPath.reset()
        checkPath.moveTo(cx - 7f * density, cy)
        checkPath.lineTo(cx - 2f * density, cy + 5f * density)
        checkPath.lineTo(cx + 8f * density, cy - 6f * density)
        val measure = PathMeasure(checkPath, false)
        drawnCheck.reset()
        measure.getSegment(0f, measure.length * checkAmount, drawnCheck, true)
        canvas.drawPath(drawnCheck, checkPaint)
    }

    override fun onDetachedFromWindow() {
        selectionAnimator?.cancel()
        animate().cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)

        private fun lerp(start: Float, end: Float, fraction: Float): Float =
            start + (end - start) * fraction
    }
}
