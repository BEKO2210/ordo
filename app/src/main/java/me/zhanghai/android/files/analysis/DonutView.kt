/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.analysis

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.google.android.material.color.MaterialColors
import me.zhanghai.android.files.R
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.ui.onFirstSight
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Draws the arcs of a ring; shared by the scan view and the chart, so one can turn into the other. */
internal object Ring {
    private val bounds = RectF()

    /**
     * Arcs clockwise from 12 o'clock, [gap] pixels apart. An arc with several colours is drawn as bands, one inside
     * the other: a merged slice shows what is in it. [reveal] draws only that share of the circle, [lifted] sits
     * [lift] pixels further out and every other arc is drawn with [dim] alpha.
     */
    fun draw(
        canvas: Canvas, paint: Paint, cx: Float, cy: Float, radius: Float, stroke: Float, gap: Float,
        sweeps: FloatArray, colors: Array<IntArray>, reveal: Float = 1f, lifted: Int = -1, lift: Float = 0f,
        dim: Float = 1f, alpha: Float = 1f
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.BUTT
        paint.shader = null
        val gapAngle = if (sweeps.count { it > 0.01f } > 1) Math.toDegrees((gap / radius).toDouble()).toFloat() else 0f
        val limit = 360f * reveal
        var start = 0f
        for (i in sweeps.indices) {
            val sweep = sweeps[i]
            val from = start + gapAngle / 2
            // An arc thinner than the gap keeps a hairline instead of vanishing.
            val length = min(max(sweep - gapAngle, min(sweep, 0.6f)), limit - from)
            start += sweep
            if (sweep <= 0.01f || length <= 0f) continue
            val r = radius + if (i == lifted) lift else 0f
            val bands = colors[i]
            val band = stroke / bands.size
            paint.strokeWidth = band
            for (b in bands.indices) {
                val bandRadius = r - stroke / 2 + band * (b + 0.5f)
                bounds.set(cx - bandRadius, cy - bandRadius, cx + bandRadius, cy + bandRadius)
                paint.color = bands[b]
                paint.alpha = (255 * alpha * if (lifted < 0 || i == lifted) 1f else dim).toInt()
                canvas.drawArc(bounds, from - 90f, length, false, paint)
            }
        }
    }
}

/**
 * The user's files as a ring, one slice per file type. A tap lifts a slice and the centre names it; a second tap
 * (or one on the centre) opens it. Every slice is its own TalkBack element.
 */
class DonutView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    /** [description] is what TalkBack reads for the slice. */
    class Slice(val key: String, val value: Long, val colors: IntArray, val description: String)

    /** The three lines in the middle; [detail] is the accent line that invites a tap. */
    class Center(val caption: String, val value: String, val detail: String? = null)

    /** A slice was lifted, or null when the selection was dropped. */
    var onSelect: ((Slice?) -> Unit)? = null

    /** A lifted slice was tapped again, or the centre while one was lifted. */
    var onOpen: ((Slice) -> Unit)? = null

    /** The centre was tapped with nothing lifted. */
    var onCenterClick: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val gap = 2.5f * density
    private val liftBy = 5 * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    private val captionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(13f)
        textAlign = Paint.Align.CENTER
        color = MaterialColors.getColor(this@DonutView, android.R.attr.textColorSecondary)
    }
    private val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(26f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        color = MaterialColors.getColor(this@DonutView, android.R.attr.textColorPrimary)
    }
    private val detailPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(13f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        color = ContextCompat.getColor(context, R.color.ai_accent)
    }

    private var slices: List<Slice> = emptyList()
    private var sweeps = FloatArray(0)
    private var colors: Array<IntArray> = emptyArray()
    private var center = Center("", "")
    private var selected = -1
    private var pressed = -1
    private var reveal = 1f
    private var centerShown = 1f
    private var lift = 0f
    private var revealAnimator: ValueAnimator? = null
    private var liftAnimator: ValueAnimator? = null
    private val helper = Access()

    /** Geometry of the ring, for a view that wants to land exactly on it. */
    val ringStroke: Float get() = 22 * density
    val ringRadius: Float get() = min(width, height) / 2f - ringStroke / 2 - liftBy - density
    val ringCenterX: Float get() = width / 2f
    val ringCenterY: Float get() = height / 2f

    /** Degrees per slice as drawn, and its colours, in slice order. */
    val drawnSweeps: FloatArray get() = sweeps
    val drawnColors: Array<IntArray> get() = colors

    init {
        ViewCompat.setAccessibilityDelegate(this, helper)
        isFocusable = true
    }

    /** [entrance] sweeps the ring in once it is on screen; without it the ring simply stands there. */
    fun setSlices(slices: List<Slice>, entrance: Boolean) {
        this.slices = slices
        sweeps = AnalysisChart.extents(slices.map { it.value }, 360f, AnalysisChart.MIN_SLICE_SWEEP)
        colors = Array(slices.size) { slices[it].colors }
        selected = -1
        lift = 0f
        revealAnimator?.cancel()
        if (entrance && Motion.any) {
            reveal = 0f
            centerShown = 0f
            onFirstSight {
                revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 900
                    startDelay = 150
                    interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
                    addUpdateListener {
                        reveal = it.animatedValue as Float
                        centerShown = ((reveal - 0.4f) / 0.6f).coerceIn(0f, 1f)
                        invalidate()
                    }
                    start()
                }
            }
        } else {
            reveal = 1f
            centerShown = 1f
        }
        helper.invalidateRoot()
        invalidate()
    }

    /** Draws nothing until [land]: another view's ring is on its way to become this one. */
    fun await() {
        revealAnimator?.cancel()
        reveal = 0f
        centerShown = 0f
        invalidate()
    }

    /** The ring is there from one frame to the next, as the other view lets go of its own; the centre fades in. */
    fun land() {
        reveal = 1f
        revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            addUpdateListener { centerShown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun setCenter(center: Center) {
        this.center = center
        invalidate()
    }

    /** Lifts the slice with [key] without telling the listener, e.g. after a rotation. */
    fun select(key: String?) {
        val index = slices.indexOfFirst { it.key == key }
        if (index == selected) return
        // Coming down, the slice that was up stays the lifted one until it has landed.
        if (index >= 0) selected = index
        liftAnimator?.cancel()
        val to = if (index >= 0) 1f else 0f
        if (Motion.any && isShown) {
            liftAnimator = ValueAnimator.ofFloat(if (index >= 0) 0f else lift, to).apply {
                duration = 200
                interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
                addUpdateListener {
                    lift = it.animatedValue as Float
                    if (index < 0 && lift == 0f) selected = -1
                    invalidate()
                }
                start()
            }
        } else {
            lift = to
            selected = index
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (revealAnimator?.isRunning == true) {
            revealAnimator?.cancel()
            reveal = 1f
            centerShown = 1f
        }
        liftAnimator?.end()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = ringCenterX
        val cy = ringCenterY
        val radius = ringRadius
        if (radius <= 0 || slices.isEmpty()) return
        Ring.draw(canvas, paint, cx, cy, radius, ringStroke, gap, sweeps, colors, reveal, selected, liftBy * lift,
            1f - 0.6f * lift)
        if (pressed >= 0) drawPress(canvas, cx, cy, radius)
        drawCenter(canvas, cx, cy, radius - ringStroke / 2 - 10 * density)
    }

    // Touch feedback: the slice under the finger lights up.
    private fun drawPress(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val r = radius + if (pressed == selected) liftBy * lift else 0f
        pressPaint.strokeWidth = ringStroke
        pressPaint.color = 0x40FFFFFF
        val start = (0 until pressed).sumOf { sweeps[it].toDouble() }.toFloat()
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, start - 90f, sweeps[pressed], false, pressPaint)
    }

    private fun drawCenter(canvas: Canvas, cx: Float, cy: Float, room: Float) {
        val alpha = (255 * centerShown).toInt()
        val width = room * 1.8f
        val lines = listOfNotNull(center.caption to captionPaint, center.value to valuePaint,
            center.detail?.let { it to detailPaint })
        val spacing = 4 * density
        // Never wider than the hole: a long value shrinks, a long name is shortened.
        val base = valuePaint.textSize
        valuePaint.textSize = base * min(1f, width / valuePaint.measureText(center.value).coerceAtLeast(1f))
        var y = cy - (lines.sumOf { (it.second.descent() - it.second.ascent()).toDouble() }.toFloat() +
            spacing * (lines.size - 1)) / 2
        for ((text, textPaint) in lines) {
            y -= textPaint.ascent()
            textPaint.alpha = alpha
            canvas.drawText(TextUtils.ellipsize(text, textPaint, width, TextUtils.TruncateAt.END).toString(), cx, y,
                textPaint)
            y += textPaint.descent() + spacing
        }
        valuePaint.textSize = base
    }

    /** The slice at a point, -2 for the centre, -1 for neither. A thin slice is easier to hit than it is wide. */
    private fun hit(x: Float, y: Float): Int {
        val distance = hypot(x - ringCenterX, y - ringCenterY)
        val reach = 13 * density
        if (distance < ringRadius - ringStroke / 2 - reach) return -2
        if (distance > ringRadius + ringStroke / 2 + liftBy + reach || slices.isEmpty()) return -1
        val angle = (Math.toDegrees(atan2((y - ringCenterY).toDouble(), (x - ringCenterX).toDouble())).toFloat() + 450f) % 360f
        // 48 dp along the ring, in degrees: what a finger needs.
        val finger = Math.toDegrees((48 * density / ringRadius).toDouble()).toFloat()
        var start = 0f
        var inside = -1
        var nearest = -1
        var nearestDistance = Float.MAX_VALUE
        for (i in sweeps.indices) {
            val middle = start + sweeps[i] / 2
            val away = min(Math.abs(angle - middle), 360f - Math.abs(angle - middle))
            if (angle >= start && angle < start + sweeps[i]) inside = i
            if (sweeps[i] < finger && away <= finger / 2 && away < nearestDistance) {
                nearest = i
                nearestDistance = away
            }
            start += sweeps[i]
        }
        return if (nearest >= 0) nearest else inside
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = hit(event.x, event.y)
                pressed = index.coerceAtLeast(-1)
                invalidate()
                // Outside the ring the touch belongs to the page, which scrolls.
                return index != -1 || selected >= 0
            }
            MotionEvent.ACTION_UP -> {
                val index = hit(event.x, event.y)
                val wasPressed = pressed
                pressed = -1
                invalidate()
                when {
                    index >= 0 && index == wasPressed -> tap(index)
                    index == -2 -> {
                        performClick()
                        if (selected >= 0 && lift > 0f) onOpen?.invoke(slices[selected]) else onCenterClick?.invoke()
                    }
                    index == -1 && selected >= 0 -> {
                        select(null)
                        onSelect?.invoke(null)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = -1
                invalidate()
            }
        }
        return true
    }

    private fun tap(index: Int) {
        performClick()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (index == selected && lift > 0f) {
            onOpen?.invoke(slices[index])
        } else {
            select(slices[index].key)
            onSelect?.invoke(slices[index])
        }
    }

    override fun performClick(): Boolean = super.performClick()

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        helper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    // TalkBack: one element per slice; activating it opens the slice, as a row would.
    private inner class Access : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int = hit(x, y).takeIf { it >= 0 } ?: INVALID_ID

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            ids += slices.indices
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val slice = slices.getOrNull(id)
            node.contentDescription = slice?.description.orEmpty()
            node.className = android.widget.Button::class.java.name
            node.isClickable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            // A finger-sized square on the middle of the slice.
            val middle = Math.toRadians(((0 until id).sumOf { sweeps[it].toDouble() } + sweeps.getOrElse(id) { 0f } / 2 - 90))
            val x = ringCenterX + ringRadius * cos(middle).toFloat()
            val y = ringCenterY + ringRadius * sin(middle).toFloat()
            val half = 24 * density
            @Suppress("DEPRECATION")
            node.setBoundsInParent(Rect((x - half).toInt(), (y - half).toInt(), (x + half).toInt(), (y + half).toInt()))
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            slices.getOrNull(id)?.let { onOpen?.invoke(it) }
            return true
        }
    }
}
