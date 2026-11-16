/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.colorpicker

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.ui.onFirstSight
import me.zhanghai.android.files.util.getColorByAttr

/** A tiny Ordo home screen that previews a color without adding another accessibility target. */
class ColorPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val windowBackground = context.getColorByAttr(android.R.attr.colorBackground)
    private val surface = MaterialColors.getColor(
        context, MaterialR.attr.colorSurface, windowBackground
    )
    private val onSurface = MaterialColors.getColor(
        context, MaterialR.attr.colorOnSurface, Color.BLACK
    )
    private val raisedSurface = ColorUtils.blendARGB(surface, onSurface, 0.06f)
    private val outline = MaterialColors.getColor(
        context, MaterialR.attr.colorOutline, ColorUtils.blendARGB(surface, onSurface, 0.24f)
    )
    private val onSurfaceVariant = MaterialColors.getColor(
        context, MaterialR.attr.colorOnSurfaceVariant, onSurface
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    @ColorInt
    private var oldAccent = context.getColorByAttr(androidx.appcompat.R.attr.colorPrimary)
    @ColorInt
    private var accent = oldAccent
    private var accentMix = 1f
    private var storageFill = if (Motion.full) 0f else STORAGE_FILL
    private var colorAnimator: ValueAnimator? = null
    private var storageAnimator: ValueAnimator? = null

    fun setAccent(@ColorInt color: Int, animate: Boolean = true) {
        if (color == accent && accentMix == 1f) return
        colorAnimator?.cancel()
        colorAnimator = null
        if (!animate || !Motion.any || width == 0) {
            oldAccent = color
            accent = color
            accentMix = 1f
            invalidate()
            return
        }
        oldAccent = displayedAccent()
        accent = color
        accentMix = 0f
        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = EMPHASIZED
            addUpdateListener {
                accentMix = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun revealStorage() {
        storageAnimator?.cancel()
        storageAnimator = null
        if (!Motion.full) {
            storageFill = STORAGE_FILL
            invalidate()
            return
        }
        storageFill = 0f
        invalidate()
        onFirstSight {
            if (!Motion.full) {
                storageFill = STORAGE_FILL
                invalidate()
            } else {
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
        }
    }

    override fun onDetachedFromWindow() {
        colorAnimator?.cancel()
        colorAnimator = null
        storageAnimator?.cancel()
        storageAnimator = null
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
        val isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val searchTop = top + 8f * density
        box.set(contentLeft, searchTop, contentRight, searchTop + 15f * density)
        paint.color = raisedSurface
        canvas.drawRoundRect(box, 8f * density, 8f * density, paint)
        paint.color = onSurfaceVariant
        paint.alpha = 105
        val searchIconX = if (isRtl) contentRight - 10f * density else contentLeft + 10f * density
        canvas.drawCircle(searchIconX, searchTop + 7.5f * density, 2.5f * density, paint)
        val searchTextLeft = if (isRtl) contentLeft + 24f * density else contentLeft + 16f * density
        val searchTextRight = if (isRtl) contentRight - 16f * density else contentRight - 24f * density
        box.set(searchTextLeft, searchTop + 6.2f * density, searchTextRight, searchTop + 8.8f * density)
        canvas.drawRoundRect(box, 1.3f * density, 1.3f * density, paint)
        paint.alpha = 255

        val tileTop = searchTop + 22f * density
        val gap = 7f * density
        val tileWidth = (contentRight - contentLeft - gap) / 2f
        val accentTileLeft = if (isRtl) contentRight - tileWidth else contentLeft
        box.set(accentTileLeft, tileTop, accentTileLeft + tileWidth, tileTop + 27f * density)
        drawAccentRoundRect(canvas, box, 9f * density)
        box.offset(if (isRtl) -(tileWidth + gap) else tileWidth + gap, 0f)
        paint.color = raisedSurface
        canvas.drawRoundRect(box, 9f * density, 9f * density, paint)
        paint.color = outline
        paint.alpha = 150
        canvas.drawCircle(box.centerX(), box.centerY(), 4f * density, paint)
        paint.alpha = 255

        val barTop = tileTop + 35f * density
        val fabRadius = 9f * density
        val fabCenterX = if (isRtl) contentLeft + fabRadius else contentRight - fabRadius
        val barLeft = if (isRtl) fabCenterX + fabRadius + 8f * density else contentLeft
        val barRight = if (isRtl) contentRight else fabCenterX - fabRadius - 8f * density
        box.set(barLeft, barTop, barRight, barTop + 6f * density)
        paint.color = outline
        canvas.drawRoundRect(box, 3f * density, 3f * density, paint)
        if (isRtl) {
            box.left = box.right - box.width() * storageFill
        } else {
            box.right = box.left + box.width() * storageFill
        }
        drawAccentRoundRect(canvas, box, 3f * density)
        drawAccentCircle(canvas, fabCenterX, barTop + 3f * density, fabRadius)

        paint.strokeWidth = 1.6f * density
        paint.strokeCap = Paint.Cap.ROUND
        drawAccentContrastCrossFade {
            canvas.drawLine(
                fabCenterX - 3f * density, barTop + 3f * density,
                fabCenterX + 3f * density, barTop + 3f * density, paint
            )
            canvas.drawLine(fabCenterX, barTop, fabCenterX, barTop + 6f * density, paint)
        }
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

    private inline fun drawAccentContrastCrossFade(draw: () -> Unit) {
        if (accentMix < 1f) {
            paint.color = contrastColor(oldAccent)
            paint.alpha = (255 * (1f - accentMix)).toInt()
            draw()
        }
        paint.color = contrastColor(accent)
        paint.alpha = (255 * accentMix).toInt()
        draw()
        paint.alpha = 255
    }

    @ColorInt
    private fun contrastColor(@ColorInt background: Int): Int =
        if (ColorUtils.calculateContrast(Color.WHITE, background) >= 3.0) Color.WHITE else Color.BLACK

    @ColorInt
    private fun displayedAccent(): Int = android.animation.ArgbEvaluator().evaluate(
        accentMix, oldAccent, accent
    ) as Int

    companion object {
        private const val STORAGE_FILL = 0.72f
        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
