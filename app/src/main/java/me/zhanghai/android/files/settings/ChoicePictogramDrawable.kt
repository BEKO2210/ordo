/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.settings

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.util.getColorByAttr
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import com.google.android.material.R as MaterialR

/**
 * One small drawn picture per choice (a sun, a moon, a phone ...) in a 56-unit box with 2-unit strokes, so it is 2 dp
 * thick at 56 dp. The resting state is the finished picture; [play] runs its animation once, and does nothing when
 * motion is off. In [icon] mode it is drawn larger, without a tile and with a thicker stroke, for 24 dp.
 */
class ChoicePictogramDrawable(
    context: Context,
    val kind: Kind,
    private val icon: Boolean = false
) : Drawable() {
    enum class Kind(val duration: Long) {
        NIGHT_FOLLOW(320), NIGHT_OFF(300), NIGHT_ON(350), NIGHT_TIME(500), NIGHT_BATTERY(280),
        MOTION_FULL(560), MOTION_SUBTLE(260), MOTION_OFF(0),
        HEADER_TOP(250), HEADER_BOTTOM(250)
    }

    private val density = context.resources.displayMetrics.density
    private val ink = context.getColorByAttr(MaterialR.attr.colorOnSurface)
    private val muted = context.getColorByAttr(MaterialR.attr.colorOnSurfaceVariant)
    private val accent = context.getColorByAttr(androidx.appcompat.R.attr.colorPrimary)
    private val tile = context.getColorByAttr(MaterialR.attr.colorSurfaceVariant)

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = if (icon) 3.4f else 2f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    private val emphasizedCurve = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val decelerate = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    private var drawableAlpha = 255

    // Raw time 0..1 of the running animation; 1 is the finished picture.
    private var time = 1f
    private var decorate = true
    private var animator: ValueAnimator? = null

    private val scratch = Path()
    private val rect = RectF()
    private val arc = Path().apply { arcTo(RectF(12.5f, 20.5f, 43.5f, 51.5f), 180f, 180f) }
    private val arcMeasure = PathMeasure(arc, false)
    private val point = FloatArray(2)
    private val crescentPath = crescent(1f)

    private val size = ((if (icon) 24 else 56) * density).toInt()

    override fun getIntrinsicWidth(): Int = size

    override fun getIntrinsicHeight(): Int = size

    /** Plays the picture's animation once from its start; with motion off it stays in its final state. */
    fun play() {
        stop()
        if (!Motion.any || kind.duration == 0L) return
        decorate = Motion.full
        time = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = kind.duration
            interpolator = LinearInterpolator()
            addUpdateListener {
                time = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
        invalidateSelf()
    }

    fun stop() {
        animator?.cancel()
        animator = null
        time = 1f
        invalidateSelf()
    }

    private fun seg(from: Float, to: Float): Float = ((time - from) / (to - from)).coerceIn(0f, 1f)

    private fun emphasized(x: Float) = emphasizedCurve.getInterpolation(x)

    private fun alpha(color: Int, factor: Float = 1f): Int =
        ColorUtils.setAlphaComponent(
            color, (Color.alpha(color) * factor * drawableAlpha / 255f).toInt().coerceIn(0, 255)
        )

    private fun strokeIn(color: Int, factor: Float = 1f) = stroke.also { it.color = alpha(color, factor) }

    private fun fillIn(color: Int, factor: Float = 1f) = fill.also { it.color = alpha(color, factor) }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        val unit = b.width() / 56f
        canvas.scale(unit, unit)
        if (!icon) {
            rect.set(0f, 0f, 56f, 56f)
            canvas.drawRoundRect(rect, 16f, 16f, fillIn(tile, 0.7f))
        } else {
            canvas.translate(28f, 28f)
            canvas.scale(1.3f, 1.3f)
            canvas.translate(-28f, -28f)
        }
        when (kind) {
            Kind.NIGHT_FOLLOW -> if (icon) drawHalfDisc(canvas) else drawHalfPhone(canvas)
            Kind.NIGHT_OFF -> drawSun(canvas)
            Kind.NIGHT_ON -> drawMoonAndStars(canvas)
            Kind.NIGHT_TIME -> drawDayArc(canvas)
            Kind.NIGHT_BATTERY -> drawBatterySaver(canvas)
            Kind.MOTION_FULL, Kind.MOTION_SUBTLE, Kind.MOTION_OFF -> drawBars(canvas)
            Kind.HEADER_TOP, Kind.HEADER_BOTTOM -> drawHeader(canvas)
        }
        canvas.restore()
    }

    private fun drawSun(canvas: Canvas) {
        val e = emphasized(seg(0f, 1f))
        canvas.drawCircle(28f, 28f, 7f, fillIn(accent, 0.18f))
        canvas.drawCircle(28f, 28f, 7f, strokeIn(accent))
        val rays = strokeIn(accent, e)
        for (i in 0 until 8) {
            val angle = i * PI / 4
            val from = 11.5f
            val to = from + 4.5f * e
            canvas.drawLine(
                28f + cos(angle).toFloat() * from, 28f + sin(angle).toFloat() * from,
                28f + cos(angle).toFloat() * to, 28f + sin(angle).toFloat() * to, rays
            )
        }
    }

    private fun crescent(radius: Float): Path {
        val outer = Path().apply { addCircle(0f, 0f, radius, Path.Direction.CW) }
        val cut = Path().apply { addCircle(radius * 0.62f, -radius * 0.5f, radius * 0.82f, Path.Direction.CW) }
        return Path(outer).apply { op(cut, Path.Op.DIFFERENCE) }
    }

    private fun drawCrescent(canvas: Canvas, cx: Float, cy: Float, radius: Float, factor: Float = 1f) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(radius, radius)
        // Strokes are measured in the scaled space, so the paint is divided back to keep 2 units.
        val width = stroke.strokeWidth
        stroke.strokeWidth = width / radius
        canvas.drawPath(crescentPath, fillIn(accent, 0.18f * factor))
        canvas.drawPath(crescentPath, strokeIn(accent, factor))
        stroke.strokeWidth = width
        canvas.restore()
    }

    private fun drawSparkle(canvas: Canvas, cx: Float, cy: Float, radius: Float, factor: Float) {
        if (factor <= 0f) return
        scratch.reset()
        scratch.moveTo(cx, cy - radius)
        scratch.quadTo(cx, cy, cx + radius, cy)
        scratch.quadTo(cx, cy, cx, cy + radius)
        scratch.quadTo(cx, cy, cx - radius, cy)
        scratch.quadTo(cx, cy, cx, cy - radius)
        canvas.save()
        val scale = 0.7f + 0.3f * factor
        canvas.scale(scale, scale, cx, cy)
        canvas.drawPath(scratch, fillIn(accent, factor))
        canvas.restore()
    }

    private fun drawMoonAndStars(canvas: Canvas) {
        val slide = if (time < 1f) decelerate.getInterpolation(seg(0f, 0.85f)) else 1f
        canvas.save()
        canvas.translate((1f - slide) * 12f, -(1f - slide) * 10f)
        drawCrescent(canvas, 26f, 30f, 12f, slide)
        canvas.restore()
        if (icon) return
        // Stars are decoration: without full motion they are simply there.
        val starOne = if (time < 1f && decorate) seg(0.3f, 0.8f) else 1f
        val starTwo = if (time < 1f && decorate) seg(0.5f, 1f) else 1f
        drawSparkle(canvas, 41f, 15f, 4.5f, starOne)
        drawSparkle(canvas, 44.5f, 29f, 3f, starTwo)
    }

    private fun drawHalfPhone(canvas: Canvas) {
        rect.set(17f, 7f, 39f, 49f)
        scratch.reset()
        scratch.addRoundRect(rect, 5f, 5f, Path.Direction.CW)
        drawHalf(canvas, scratch, 17f, 11f, 7f, 49f)
        canvas.drawPath(scratch, strokeIn(ink))
    }

    private fun drawHalfDisc(canvas: Canvas) {
        scratch.reset()
        scratch.addCircle(28f, 28f, 13f, Path.Direction.CW)
        drawHalf(canvas, scratch, 15f, 13f, 15f, 41f)
        canvas.drawPath(scratch, strokeIn(ink))
    }

    // The dark half sweeps in from the left edge of [shape]; the light half is a faint fill.
    private fun drawHalf(canvas: Canvas, shape: Path, left: Float, halfWidth: Float, top: Float, bottom: Float) {
        canvas.save()
        canvas.clipPath(shape)
        canvas.drawPath(shape, fillIn(ink, 0.1f))
        val e = emphasized(seg(0f, 1f))
        rect.set(left, top, left + halfWidth * e, bottom)
        canvas.drawRect(rect, fillIn(ink, 0.85f))
        canvas.restore()
    }

    private fun drawDayArc(canvas: Canvas) {
        canvas.save()
        canvas.translate(0f, -4f)
        canvas.drawPath(arc, strokeIn(muted, 0.6f))
        canvas.drawCircle(7f, 40f, 3f, fillIn(accent, 0.18f))
        canvas.drawCircle(7f, 40f, 3f, strokeIn(accent))
        drawCrescent(canvas, 49f, 40f, 4.5f)
        val fraction = 0.5f * emphasized(seg(0f, 1f))
        arcMeasure.getPosTan(arcMeasure.length * fraction, point, null)
        canvas.drawCircle(point[0], point[1], 3f, fillIn(accent))
        canvas.restore()
    }

    private fun drawBatterySaver(canvas: Canvas) {
        rect.set(9f, 19f, 41f, 37f)
        canvas.drawRoundRect(rect, 5f, 5f, strokeIn(ink))
        rect.set(42.5f, 24.5f, 46f, 31.5f)
        canvas.drawRoundRect(rect, 1.5f, 1.5f, fillIn(ink))
        val e = emphasized(seg(0f, 1f))
        canvas.save()
        val scale = 0.8f + 0.2f * e
        canvas.scale(scale, scale, 25f, 28f)
        drawCrescent(canvas, 24f, 28f, 5.5f, e)
        canvas.restore()
    }

    private fun drawBars(canvas: Canvas) {
        val heights = if (kind == Kind.MOTION_OFF) HEIGHTS_OFF else HEIGHTS_ON
        val color = if (kind == Kind.MOTION_OFF) muted else accent
        val staggered = kind == Kind.MOTION_FULL
        val length = if (staggered) 260f / kind.duration else 1f
        for (i in heights.indices) {
            val start = if (staggered) i * 40f / kind.duration else 0f
            val rise = if (kind == Kind.MOTION_OFF) 1f else emphasized(seg(start, start + length))
            val x = 17f + 11f * i
            val h = heights[i] * rise
            rect.set(x - 3f, 42f - h, x + 3f, 42f)
            canvas.drawRoundRect(rect, 3f, 3f, fillIn(color, if (kind == Kind.MOTION_OFF) 0.6f else 1f))
        }
        if (kind == Kind.MOTION_FULL) {
            // One quiet blink once the bars stand; only with full motion.
            val blink = if (time < 1f && decorate) 1f - 0.75f * sin(PI * seg(0.65f, 1f)).toFloat() else 1f
            drawSparkle(canvas, 43f, 14f, 5f, blink)
        }
    }

    private fun drawHeader(canvas: Canvas) {
        rect.set(15f, 7f, 41f, 49f)
        canvas.drawRoundRect(rect, 5f, 5f, strokeIn(ink))
        val lines = strokeIn(ink, 0.35f)
        canvas.drawLine(20f, 26f, 36f, 26f, lines)
        canvas.drawLine(20f, 31f, 29f, 31f, lines)
        val top = 12f
        val bottom = 36f
        val target = if (kind == Kind.HEADER_TOP) top else bottom
        val origin = if (kind == Kind.HEADER_TOP) bottom else top
        val y = origin + (target - origin) * emphasized(seg(0f, 1f))
        rect.set(19f, y, 37f, y + 8f)
        canvas.drawRoundRect(rect, 2.5f, 2.5f, fillIn(accent))
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private val HEIGHTS_OFF = floatArrayOf(12f, 12f, 12f)
private val HEIGHTS_ON = floatArrayOf(12f, 20f, 28f)
