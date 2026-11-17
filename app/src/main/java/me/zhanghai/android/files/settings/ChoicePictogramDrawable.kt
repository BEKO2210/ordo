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
import android.view.View
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
 * thick at 56 dp; the tile in the dialogs is 89 x 55 dp (φ : 1) and the box sits centred in it. The resting state is
 * the finished picture; [play] runs its animation once, and does nothing when motion is off. In [icon] mode it is drawn
 * larger, without a tile and with a thicker stroke, for 24 dp.
 */
class ChoicePictogramDrawable(
    context: Context,
    val kind: Kind,
    private val icon: Boolean = false
) : Drawable() {
    enum class Kind(val duration: Long) {
        NIGHT_FOLLOW(320), NIGHT_OFF(300), NIGHT_ON(350), NIGHT_TIME(500), NIGHT_BATTERY(280),
        MOTION_FULL(560), MOTION_SUBTLE(260), MOTION_OFF(0),
        HEADER_TOP(250), HEADER_BOTTOM(250),
        AI_TURBO(420), AI_BALANCED(420), AI_ECO(420),
        TRASH_SHORT(480), TRASH_MEDIUM(480), TRASH_LONG(480), TRASH_NEVER(520),
        ELLIPSIZE_START(320), ELLIPSIZE_MIDDLE(320), ELLIPSIZE_END(320), ELLIPSIZE_MARQUEE(600),
        APK_INSTALL(400), APK_VIEW(420), APK_ASK(460)
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

    private val gaugeRect = RectF(10f, 16f, 46f, 52f)
    private val ringRect = RectF(10f, 10f, 46f, 46f)
    private val infinity = Path().apply {
        moveTo(28f, 28f)
        cubicTo(25f, 22f, 17f, 22f, 17f, 28f)
        cubicTo(17f, 34f, 25f, 34f, 28f, 28f)
        cubicTo(31f, 22f, 39f, 22f, 39f, 28f)
        cubicTo(39f, 34f, 31f, 34f, 28f, 28f)
    }
    private val infinityMeasure = PathMeasure(infinity, false)
    private val question = Path().apply {
        moveTo(21.5f, 21f)
        arcTo(RectF(21.5f, 13f, 34.5f, 26f), 180f, 270f)
        lineTo(28f, 32f)
    }
    private val questionMeasure = PathMeasure(question, false)
    private val boxBody = Path().apply {
        moveTo(14f, 28f)
        lineTo(14f, 41f)
        quadTo(14f, 46f, 19f, 46f)
        lineTo(37f, 46f)
        quadTo(42f, 46f, 42f, 41f)
        lineTo(42f, 28f)
    }
    private val tray = Path().apply {
        moveTo(11f, 28f)
        lineTo(11f, 46f)
        lineTo(45f, 46f)
        lineTo(45f, 28f)
    }

    private val width = ((if (icon) 24 else 89) * density).toInt()
    private val height = ((if (icon) 24 else 55) * density).toInt()

    override fun getIntrinsicWidth(): Int = width

    override fun getIntrinsicHeight(): Int = height

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
        if (!icon) {
            // Tile of any proportion (φ : 1 in the choice dialogs, 89 x 55 dp); the 56-unit picture sits centred in it.
            val r = TILE_RADIUS_DP * density
            rect.set(0f, 0f, b.width().toFloat(), b.height().toFloat())
            canvas.drawRoundRect(rect, r, r, fillIn(tile, 0.7f))
            val side = minOf(b.width(), b.height())
            canvas.translate((b.width() - side) / 2f, (b.height() - side) / 2f)
        }
        val unit = (if (icon) b.width() else minOf(b.width(), b.height())) / 56f
        canvas.scale(unit, unit)
        if (icon) {
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
            Kind.AI_TURBO, Kind.AI_BALANCED, Kind.AI_ECO -> drawGauge(canvas)
            Kind.TRASH_SHORT, Kind.TRASH_MEDIUM, Kind.TRASH_LONG -> drawRetention(canvas)
            Kind.TRASH_NEVER -> drawNever(canvas)
            Kind.ELLIPSIZE_START, Kind.ELLIPSIZE_MIDDLE, Kind.ELLIPSIZE_END, Kind.ELLIPSIZE_MARQUEE ->
                drawEllipsize(canvas)
            Kind.APK_INSTALL -> drawInstall(canvas)
            Kind.APK_VIEW -> drawOpenBox(canvas)
            Kind.APK_ASK -> drawAsk(canvas)
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

    // A gauge: the needle swings from the left stop to its position and the track fills behind it.
    private fun drawGauge(canvas: Canvas) {
        val target = when (kind) {
            Kind.AI_TURBO -> 0.85f
            Kind.AI_BALANCED -> 0.5f
            else -> 0.18f
        }
        val current = target * emphasized(seg(0f, 1f))
        canvas.drawArc(gaugeRect, 180f, 180f, false, strokeIn(muted, 0.6f))
        if (current > 0.01f) canvas.drawArc(gaugeRect, 180f, 180f * current, false, strokeIn(accent))
        val angle = PI * (1f + current)
        val cx = 28f
        val cy = 34f
        canvas.drawLine(
            cx, cy, cx + cos(angle).toFloat() * 13f, cy + sin(angle).toFloat() * 13f, strokeIn(ink)
        )
        canvas.drawCircle(cx, cy, 3f, fillIn(accent))
    }

    // A ring that fills clockwise from the top, with a hand at its end: a longer arc is a longer period.
    private fun drawRetention(canvas: Canvas) {
        val target = when (kind) {
            Kind.TRASH_SHORT -> 0.25f
            Kind.TRASH_MEDIUM -> 0.5f
            else -> 0.75f
        }
        val current = target * emphasized(seg(0f, 1f))
        canvas.drawCircle(28f, 28f, 18f, strokeIn(muted, 0.45f))
        if (current > 0.005f) canvas.drawArc(ringRect, -90f, 360f * current, false, strokeIn(accent))
        val angle = (-90.0 + 360.0 * current) * PI / 180.0
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        canvas.drawLine(28f, 28f, 28f + dx * 9f, 28f + dy * 9f, strokeIn(ink))
        canvas.drawCircle(28f + dx * 18f, 28f + dy * 18f, 3f, fillIn(accent))
        canvas.drawCircle(28f, 28f, 2.2f, fillIn(ink))
    }

    // A closed ring with an infinity sign that is drawn in once.
    private fun drawNever(canvas: Canvas) {
        canvas.drawCircle(28f, 28f, 18f, strokeIn(ink, 0.45f))
        val e = emphasized(seg(0f, 1f))
        scratch.reset()
        infinityMeasure.getSegment(0f, infinityMeasure.length * e, scratch, true)
        canvas.drawPath(scratch, strokeIn(accent))
    }

    // A line of text with three dots where the name is cut; the marquee bar slides once.
    private fun drawEllipsize(canvas: Canvas) {
        canvas.save()
        if (layoutDirection == View.LAYOUT_DIRECTION_RTL) canvas.scale(-1f, 1f, 28f, 0f)
        if (kind == Kind.ELLIPSIZE_MARQUEE) {
            canvas.clipRect(8f, 20f, 48f, 36f)
            val offset = -24f * sin(PI * emphasized(seg(0f, 1f))).toFloat()
            rect.set(8f + offset, 25f, 64f + offset, 31f)
            canvas.drawRoundRect(rect, 3f, 3f, fillIn(accent))
            canvas.restore()
            return
        }
        val firstEnd: Float
        val secondStart: Float
        val dotsFrom: Float
        when (kind) {
            Kind.ELLIPSIZE_START -> { firstEnd = 8f; secondStart = 25f; dotsFrom = 11f }
            Kind.ELLIPSIZE_MIDDLE -> { firstEnd = 19f; secondStart = 37f; dotsFrom = 23f }
            else -> { firstEnd = 31f; secondStart = 48f; dotsFrom = 36f }
        }
        if (firstEnd > 8f) {
            rect.set(8f, 25f, firstEnd, 31f)
            canvas.drawRoundRect(rect, 3f, 3f, fillIn(ink, 0.55f))
        }
        if (secondStart < 48f) {
            rect.set(secondStart, 25f, 48f, 31f)
            canvas.drawRoundRect(rect, 3f, 3f, fillIn(ink, 0.55f))
        }
        for (i in 0 until 3) {
            val start = i * 40f / kind.duration
            val pop = emphasized(seg(start, start + 200f / kind.duration))
            canvas.drawCircle(dotsFrom + 5f * i, 28f, 1.9f * pop, fillIn(accent))
        }
        canvas.restore()
    }

    // The package drops into the device tray, the arrow above it follows.
    private fun drawInstall(canvas: Canvas) {
        val e = emphasized(seg(0f, 1f))
        canvas.drawPath(tray, strokeIn(ink))
        val lift = -10f * (1f - e)
        canvas.save()
        canvas.translate(0f, lift)
        rect.set(18f, 20f, 38f, 38f)
        canvas.drawRoundRect(rect, 3f, 3f, fillIn(accent, 0.18f))
        canvas.drawRoundRect(rect, 3f, 3f, strokeIn(accent))
        canvas.drawLine(18f, 27f, 38f, 27f, strokeIn(accent, 0.6f))
        canvas.restore()
        val arrow = strokeIn(accent, e)
        canvas.drawLine(28f, 6f, 28f, 14f, arrow)
        canvas.drawLine(24.5f, 11f, 28f, 14.5f, arrow)
        canvas.drawLine(31.5f, 11f, 28f, 14.5f, arrow)
    }

    // An open box: the lid flaps swing up and a sheet rises out of it.
    private fun drawOpenBox(canvas: Canvas) {
        val e = emphasized(seg(0f, 1f))
        val rise = if (time < 1f && decorate) e else 1f
        rect.set(22f, 36f - 14f * rise, 34f, 36f)
        canvas.drawRoundRect(rect, 2f, 2f, fillIn(accent, 0.25f))
        canvas.drawRoundRect(rect, 2f, 2f, strokeIn(accent, 0.8f))
        canvas.drawPath(boxBody, strokeIn(ink))
        val flaps = strokeIn(ink)
        val left = Math.toRadians(-120.0 * e)
        canvas.drawLine(14f, 28f, 14f + cos(left).toFloat() * 14f, 28f + sin(left).toFloat() * 14f, flaps)
        val right = Math.toRadians(180.0 + 120.0 * e)
        canvas.drawLine(42f, 28f, 42f + cos(right).toFloat() * 14f, 28f + sin(right).toFloat() * 14f, flaps)
    }

    // A question mark that is drawn in stroke by stroke, then its dot.
    private fun drawAsk(canvas: Canvas) {
        canvas.drawCircle(28f, 28f, 20f, strokeIn(muted, 0.5f))
        val draw = emphasized(seg(0f, 0.7f))
        scratch.reset()
        questionMeasure.getSegment(0f, questionMeasure.length * draw, scratch, true)
        canvas.drawPath(scratch, strokeIn(accent))
        canvas.drawCircle(28f, 38.5f, 1.9f * emphasized(seg(0.6f, 0.9f)), fillIn(accent))
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private const val TILE_RADIUS_DP = 13f
private val HEIGHTS_OFF = floatArrayOf(12f, 12f, 12f)
private val HEIGHTS_ON = floatArrayOf(12f, 20f, 28f)
