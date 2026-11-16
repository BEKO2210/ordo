/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.colorpicker

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import androidx.annotation.ColorInt
import com.google.android.material.R as MaterialR
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.util.getColorByAttr

/** A tiny Ordo home screen that previews a color without adding another accessibility target. */
class ColorPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val surface = context.getColorByAttr(MaterialR.attr.colorSurfaceContainerLow)
    private val raisedSurface = context.getColorByAttr(MaterialR.attr.colorSurfaceContainerHigh)
    private val outline = context.getColorByAttr(MaterialR.attr.colorOutlineVariant)
    private val onSurfaceVariant = context.getColorByAttr(MaterialR.attr.colorOnSurfaceVariant)
    private val onAccent = context.getColorByAttr(MaterialR.attr.colorOnPrimary)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    @ColorInt
    private var oldAccent = context.getColorByAttr(androidx.appcompat.R.attr.colorPrimary)
    @ColorInt
    private var accent = oldAccent
    private var accentMix = 1f
    private var storageFill = STORAGE_FILL
    private var colorAnimator: ValueAnimator? = null
    private var storageAnimator: ValueAnimator? = null

    fun setAccent(@ColorInt color: Int, animate: Boolean = true) {
        if (color == accent && accentMix == 1f) return
        colorAnimator?.cancel()
        storageAnimator?.cancel()
        if (!animate || !Motion.any || width == 0) {
            oldAccent = color
            accent = color
            accentMix = 1f
            storageFill = STORAGE_FILL
            invalidate()
            return
        }
        oldAccent = displayedAccent()
        accent = color
        accentMix = 0f
        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = EMPHASIZED
            addUpdateListener {
                accentMix = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        storageFill = 0f
        storageAnimator = ValueAnimator.ofFloat(0f, STORAGE_FILL).apply {
            duration = 400
            interpolator = EMPHASIZED
            addUpdateListener {
                storageFill = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        colorAnimator?.cancel()
        storageAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val inset = 8f * density
        val left = paddingLeft + inset
        val right = width - paddingRight - inset
        val top = paddingTop + 4f * density
        val bottom = height - paddingBottom - 4f * density
        if (right <= left || bottom <= top) return

        box.set(left, top, right, bottom)
        paint.color = surface
        canvas.drawRoundRect(box, 18f * density, 18f * density, paint)

        val contentLeft = left + 10f * density
        val contentRight = right - 10f * density
        val searchTop = top + 8f * density
        box.set(contentLeft, searchTop, contentRight, searchTop + 15f * density)
        paint.color = raisedSurface
        canvas.drawRoundRect(box, 8f * density, 8f * density, paint)
        paint.color = onSurfaceVariant
        paint.alpha = 105
        canvas.drawCircle(contentLeft + 10f * density, searchTop + 7.5f * density, 2.5f * density, paint)
        box.set(
            contentLeft + 16f * density, searchTop + 6.2f * density,
            contentRight - 24f * density, searchTop + 8.8f * density
        )
        canvas.drawRoundRect(box, 1.3f * density, 1.3f * density, paint)
        paint.alpha = 255

        val tileTop = searchTop + 22f * density
        val gap = 7f * density
        val tileWidth = (contentRight - contentLeft - gap) / 2f
        box.set(contentLeft, tileTop, contentLeft + tileWidth, tileTop + 27f * density)
        drawAccentRoundRect(canvas, box, 9f * density)
        box.offset(tileWidth + gap, 0f)
        paint.color = raisedSurface
        canvas.drawRoundRect(box, 9f * density, 9f * density, paint)
        paint.color = outline
        paint.alpha = 150
        canvas.drawCircle(box.centerX(), box.centerY(), 4f * density, paint)
        paint.alpha = 255

        val barTop = tileTop + 35f * density
        val fabRadius = 9f * density
        val fabCenterX = contentRight - fabRadius
        val barRight = fabCenterX - fabRadius - 8f * density
        box.set(contentLeft, barTop, barRight, barTop + 6f * density)
        paint.color = outline
        canvas.drawRoundRect(box, 3f * density, 3f * density, paint)
        box.right = box.left + box.width() * storageFill
        drawAccentRoundRect(canvas, box, 3f * density)
        drawAccentCircle(canvas, fabCenterX, barTop + 3f * density, fabRadius)

        paint.color = onAccent
        paint.strokeWidth = 1.6f * density
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(
            fabCenterX - 3f * density, barTop + 3f * density,
            fabCenterX + 3f * density, barTop + 3f * density, paint
        )
        canvas.drawLine(
            fabCenterX, barTop, fabCenterX, barTop + 6f * density, paint
        )
    }

    private fun drawAccentRoundRect(canvas: Canvas, rect: RectF, radius: Float) {
        drawCrossFade { canvas.drawRoundRect(rect, radius, radius, paint) }
    }

    private fun drawAccentCircle(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        drawCrossFade { canvas.drawCircle(cx, cy, radius, paint) }
    }

    private inline fun drawCrossFade(draw: () -> Unit) {
        if (accentMix < 1f) {
            paint.color = oldAccent
            paint.alpha = (255 * (1f - accentMix)).toInt()
            draw()
        }
        paint.color = accent
        paint.alpha = (255 * accentMix).toInt()
        draw()
        paint.alpha = 255
    }

    @ColorInt
    private fun displayedAccent(): Int = android.animation.ArgbEvaluator().evaluate(
        accentMix, oldAccent, accent
    ) as Int

    companion object {
        private const val STORAGE_FILL = 0.72f
        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
