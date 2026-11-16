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

    fun revealStorage(animate: Boolean = true, onReveal: () -> Unit = {}) {
        storageAnimator?.cancel()
        storageAnimator = null
        if (!animate || !Motion.full) {
            storageFill = STORAGE_FILL
            invalidate()
            onReveal()
            return
        }
        storageFill = 0f
        invalidate()
        onFirstSight {
            if (!Motion.full) {
                storageFill = STORAGE_FILL
                invalidate()
                onReveal()
            } else {
                onReveal()
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
        // Replace a reveal which is still waiting below the fold with a no-op before this view is recycled.
        onFirstSight {}
        colorAnimator?.cancel()
        colorAnimator = null
        storageAnimator?.cancel()
        storageAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val inset = 5f * density
        val left = paddingLeft + inset
        val right = width - paddingRight - inset
        val top = paddingTop + 3f * density
        val bottom = height - paddingBottom - 3f * density
        if (right <= left || bottom <= top) return

        box.set(left, top, right, bottom)
        paint.color = surface
        canvas.drawRoundRect(box, 18f * density, 18f * density, paint)

        val contentLeft = left + 7f * density
        val contentRight = right - 7f * density
        val isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val searchTop = top + 4f * density
        box.set(contentLeft, searchTop, contentRight, searchTop + 10f * density)
        paint.color = raisedSurface
        canvas.drawRoundRect(box, 5f * density, 5f * density, paint)
        paint.color = onSurfaceVariant
        paint.alpha = 105
        val searchIconX = if (isRtl) contentRight - 7f * density else contentLeft + 7f * density
        canvas.drawCircle(searchIconX, searchTop + 5f * density, 1.7f * density, paint)
        val searchTextLeft = if (isRtl) contentLeft + 16f * density else contentLeft + 11f * density
        val searchTextRight = if (isRtl) contentRight - 11f * density else contentRight - 16f * density
        box.set(searchTextLeft, searchTop + 4.1f * density, searchTextRight, searchTop + 5.9f * density)
        canvas.drawRoundRect(box, density, density, paint)
        paint.alpha = 255

        val tileTop = searchTop + 14f * density
        val gap = 5f * density
        val tileWidth = (contentRight - contentLeft - gap) / 2f
        val accentTileLeft = if (isRtl) contentRight - tileWidth else contentLeft
        box.set(accentTileLeft, tileTop, accentTileLeft + tileWidth, tileTop + 20f * density)
        drawAccentRoundRect(canvas, box, 7f * density)
        box.offset(if (isRtl) -(tileWidth + gap) else tileWidth + gap, 0f)
        paint.color = raisedSurface
        canvas.drawRoundRect(box, 7f * density, 7f * density, paint)
        paint.color = outline
        paint.alpha = 150
        canvas.drawCircle(box.centerX(), box.centerY(), 3f * density, paint)
        paint.alpha = 255

        val barTop = tileTop + 27f * density
        val fabRadius = 6f * density
        val fabCenterX = if (isRtl) contentLeft + fabRadius else contentRight - fabRadius
        val barLeft = if (isRtl) fabCenterX + fabRadius + 5f * density else contentLeft
        val barRight = if (isRtl) contentRight else fabCenterX - fabRadius - 5f * density
        box.set(barLeft, barTop, barRight, barTop + 4f * density)
        paint.color = outline
        canvas.drawRoundRect(box, 2f * density, 2f * density, paint)
        if (isRtl) {
            box.left = box.right - box.width() * storageFill
        } else {
            box.right = box.left + box.width() * storageFill
        }
        drawAccentRoundRect(canvas, box, 2f * density)
        drawAccentCircle(canvas, fabCenterX, barTop + 2f * density, fabRadius)

        paint.strokeWidth = 1.2f * density
        paint.strokeCap = Paint.Cap.ROUND
        drawAccentContrastCrossFade {
            canvas.drawLine(
                fabCenterX - 2f * density, barTop + 2f * density,
                fabCenterX + 2f * density, barTop + 2f * density, paint
            )
            canvas.drawLine(fabCenterX, barTop, fabCenterX, barTop + 4f * density, paint)
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
