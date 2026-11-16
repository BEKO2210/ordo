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
import kotlin.math.min

/** An Ordo home screen miniature that previews a color without adding another accessibility target. */
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

        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        val scale = min(
            availableWidth / (PREVIEW_WIDTH_DP * density),
            availableHeight / (PREVIEW_HEIGHT_DP * density)
        )
        if (scale <= 0f) return
        fun scaled(dp: Float) = dp * density * scale

        val inset = scaled(6f)
        val left = paddingLeft + inset
        val right = width - paddingRight - inset
        val top = paddingTop + scaled(4f)
        val bottom = height - paddingBottom - scaled(4f)
        if (right <= left || bottom <= top) return

        box.set(left, top, right, bottom)
        paint.color = surface
        canvas.drawRoundRect(box, scaled(20f), scaled(20f), paint)

        val contentLeft = left + scaled(10f)
        val contentRight = right - scaled(10f)
        val isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val searchTop = top + scaled(5f)
        box.set(contentLeft, searchTop, contentRight, searchTop + scaled(13f))
        paint.color = raisedSurface
        canvas.drawRoundRect(box, scaled(6.5f), scaled(6.5f), paint)
        paint.color = onSurfaceVariant
        paint.alpha = 105
        val searchCenterY = searchTop + scaled(6.5f)
        val searchIconX = if (isRtl) contentRight - scaled(9f) else contentLeft + scaled(9f)
        canvas.drawCircle(searchIconX, searchCenterY, scaled(2.2f), paint)
        val searchTextLeft = if (isRtl) contentLeft + scaled(20f) else contentLeft + scaled(14f)
        val searchTextRight = if (isRtl) contentRight - scaled(14f) else contentRight - scaled(20f)
        box.set(
            searchTextLeft, searchCenterY - scaled(1.2f),
            searchTextRight, searchCenterY + scaled(1.2f)
        )
        canvas.drawRoundRect(box, scaled(1.2f), scaled(1.2f), paint)
        paint.alpha = 255

        val tileTop = searchTop + scaled(20f)
        val gap = scaled(7f)
        val tileWidth = (contentRight - contentLeft - gap) / 2f
        val accentTileLeft = if (isRtl) contentRight - tileWidth else contentLeft
        box.set(accentTileLeft, tileTop, accentTileLeft + tileWidth, tileTop + scaled(26f))
        drawAccentRoundRect(canvas, box, scaled(9f))
        box.offset(if (isRtl) -(tileWidth + gap) else tileWidth + gap, 0f)
        paint.color = raisedSurface
        canvas.drawRoundRect(box, scaled(9f), scaled(9f), paint)
        paint.color = outline
        paint.alpha = 150
        canvas.drawCircle(box.centerX(), box.centerY(), scaled(4f), paint)
        paint.alpha = 255

        val barTop = tileTop + scaled(36f)
        val fabRadius = scaled(8f)
        val fabCenterX = if (isRtl) contentLeft + fabRadius else contentRight - fabRadius
        val barLeft = if (isRtl) fabCenterX + fabRadius + scaled(7f) else contentLeft
        val barRight = if (isRtl) contentRight else fabCenterX - fabRadius - scaled(7f)
        box.set(barLeft, barTop, barRight, barTop + scaled(5f))
        paint.color = outline
        canvas.drawRoundRect(box, scaled(2.5f), scaled(2.5f), paint)
        if (isRtl) {
            box.left = box.right - box.width() * storageFill
        } else {
            box.right = box.left + box.width() * storageFill
        }
        drawAccentRoundRect(canvas, box, scaled(2.5f))
        val barCenterY = barTop + scaled(2.5f)
        drawAccentCircle(canvas, fabCenterX, barCenterY, fabRadius)

        paint.strokeWidth = scaled(1.8f)
        paint.strokeCap = Paint.Cap.ROUND
        drawAccentContrastCrossFade {
            canvas.drawLine(
                fabCenterX - scaled(3f), barCenterY,
                fabCenterX + scaled(3f), barCenterY, paint
            )
            canvas.drawLine(
                fabCenterX, barCenterY - scaled(3f),
                fabCenterX, barCenterY + scaled(3f), paint
            )
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
        private const val PREVIEW_WIDTH_DP = 132f
        private const val PREVIEW_HEIGHT_DP = 84f
        private const val STORAGE_FILL = 0.72f
        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
