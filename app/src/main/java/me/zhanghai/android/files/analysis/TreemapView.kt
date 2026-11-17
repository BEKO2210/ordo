/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.analysis

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.google.android.material.color.MaterialColors
import me.zhanghai.android.files.R
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.ui.onFirstSight
import kotlin.math.max
import kotlin.math.min

/**
 * The folders that hold the space as tiles (squarified layout). One hue, more of it for bigger, as magnitude wants.
 * The tiles come from [AnalysisChart.tiles]: a dominant folder is already opened up and no tile is too small for a
 * finger, so the map stays readable whatever the storage looks like. A tap marks a tile, a second tap opens it.
 */
class TreemapView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    /**
     * [area] is the share of the map the tile is drawn with and [share] its real share, which sets the colour.
     * [description] is what TalkBack reads.
     */
    class Item(val key: String, val label: String, val detail: String, val area: Float, val share: Float,
               val description: String)

    /** A tile was marked, or null when the mark was dropped. */
    var onSelect: ((Item?) -> Unit)? = null

    /** A marked tile was tapped again. */
    var onOpen: ((Item) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val gap = 3 * density
    private val radius = 8 * density
    private val pad = 8 * density
    private val hue = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    private val surface by lazy { MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(13f)
        isFakeBoldText = true
    }
    private val detail = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(12f) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = MaterialColors.getColor(this@TreemapView, android.R.attr.textColorPrimary)
    }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(56, 255, 255, 255) }
    private var items: List<Item> = emptyList()
    private var tiles: List<RectF> = emptyList()

    // What fits on each tile, worked out when the tiles are laid out and not while drawing.
    private var labels: List<List<String>> = emptyList()
    private var progress = 1f
    private var animator: ValueAnimator? = null
    private val tmp = RectF()
    private var pressed = -1
    private var selected = -1
    private val helper = Access()

    init {
        ViewCompat.setAccessibilityDelegate(this, helper)
        isFocusable = true
    }

    /** [entrance] lets the tiles grow in once the map is on screen. */
    fun setItems(items: List<Item>, entrance: Boolean) {
        this.items = items
        selected = -1
        layoutTiles()
        animator?.cancel()
        if (entrance && Motion.any) {
            progress = 0f
            onFirstSight {
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 800
                    startDelay = 150
                    interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
                    addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        } else {
            progress = 1f
        }
        helper.invalidateRoot()
        invalidate()
    }

    /** Marks the tile with [key] without telling the listener, e.g. after a rotation. */
    fun select(key: String?) {
        selected = items.indexOfFirst { it.key == key }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutTiles()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (animator?.isRunning == true) {
            animator?.cancel()
            progress = 1f
        }
    }

    private fun layoutTiles() {
        if (width == 0 || height == 0 || items.isEmpty()) {
            tiles = emptyList()
            labels = emptyList()
            return
        }
        val total = items.sumOf { it.area.toDouble() }
        val all = width.toDouble() * height
        val out = arrayOfNulls<RectF>(items.size)
        squarify(items.indices.sortedByDescending { items[it].area }.map { it to items[it].area / total * all },
            RectF(0f, 0f, width.toFloat(), height.toFloat()), out)
        tiles = out.map { it!! }
        labels = tiles.mapIndexed { i, r -> fit(items[i], r.width() - gap - 2 * pad, r.height() - gap - 2 * pad) }
    }

    /** The name on up to two lines and the size below, as far as the tile has room; nothing on a tile too small. */
    private fun fit(item: Item, width: Float, height: Float): List<String> {
        val line = title.descent() - title.ascent()
        if (width < 34 * density || height < line) return emptyList()
        val name = if (title.measureText(item.label) <= width || height < 2 * line) {
            listOf(TextUtils.ellipsize(item.label, title, width, TextUtils.TruncateAt.END).toString())
        } else {
            // Two lines only where a space allows it; a single word is shortened, never cut in the middle.
            val count = title.breakText(item.label, true, width, null)
            val cut = item.label.lastIndexOf(' ', count).takeIf { it > 0 }
            if (cut == null) listOf(TextUtils.ellipsize(item.label, title, width, TextUtils.TruncateAt.END).toString())
            else listOf(item.label.substring(0, cut).trim(),
                TextUtils.ellipsize(item.label.substring(cut).trim(), title, width, TextUtils.TruncateAt.END).toString())
        }
        val size = detail.descent() - detail.ascent()
        return if (height >= name.size * line + 3 * density + size && detail.measureText(item.detail) <= width) {
            name + item.detail
        } else {
            name
        }
    }

    // Bruls et al.: add items to a row while the worst aspect ratio improves, then lay the row along the short side.
    private fun squarify(rest: List<Pair<Int, Double>>, box: RectF, out: Array<RectF?>) {
        if (rest.isEmpty()) return
        val side = min(box.width(), box.height()).toDouble()
        var row = listOf(rest.first())
        var i = 1
        while (i < rest.size && worst(row + rest[i], side) <= worst(row, side)) {
            row = row + rest[i]
            i++
        }
        val sum = row.sumOf { it.second }
        val thickness = (sum / side).toFloat()
        var offset = 0f
        val horizontal = box.width() >= box.height()
        for ((index, a) in row) {
            val length = (a / thickness).toFloat()
            out[index] = if (horizontal) {
                RectF(box.left, box.top + offset, box.left + thickness, box.top + offset + length)
            } else {
                RectF(box.left + offset, box.top, box.left + offset + length, box.top + thickness)
            }
            offset += length
        }
        val next = if (horizontal) RectF(box.left + thickness, box.top, box.right, box.bottom)
        else RectF(box.left, box.top + thickness, box.right, box.bottom)
        squarify(rest.drop(row.size), next, out)
    }

    private fun worst(row: List<Pair<Int, Double>>, side: Double): Double {
        val sum = row.sumOf { it.second }
        val maxA = row.maxOf { it.second }
        val minA = row.minOf { it.second }
        return max(side * side * maxA / (sum * sum), sum * sum / (side * side * minA))
    }

    override fun onDraw(canvas: Canvas) {
        val biggest = items.maxOfOrNull { it.share } ?: return
        tiles.forEachIndexed { i, r ->
            val item = items[i]
            val local = ((progress * (tiles.size + 3) - i) / 4f).coerceIn(0f, 1f)
            if (local <= 0f) return@forEachIndexed
            // Sequential: the real share of the biggest tile sets how much accent goes into the surface colour.
            val strength = 0.3f + 0.55f * (item.share / biggest)
            val color = ColorUtils.blendARGB(surface, hue, strength)
            fill.color = color
            fill.alpha = (255 * local).toInt()
            val inset = gap / 2 + (1 - local) * 8 * density
            tmp.set(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
            canvas.drawRoundRect(tmp, radius, radius, fill)
            drawLabels(canvas, i, tmp, color, local)
            // Touch feedback: the tile under the finger lights up.
            if (i == pressed) canvas.drawRoundRect(tmp, radius, radius, pressPaint)
            if (i == selected) {
                val half = ring.strokeWidth / 2
                tmp.inset(half, half)
                canvas.drawRoundRect(tmp, radius - half, radius - half, ring)
            }
        }
    }

    private fun drawLabels(canvas: Canvas, index: Int, r: RectF, background: Int, alpha: Float) {
        val lines = labels.getOrNull(index) ?: return
        if (lines.isEmpty()) return
        // Text colour by contrast with its own tile, never the series colour.
        val ink = if (ColorUtils.calculateContrast(Color.WHITE, background or 0xFF000000.toInt()) >= 4.5) Color.WHITE
        else Color.argb(255, 15, 23, 42)
        title.color = ink
        detail.color = ink
        title.alpha = (255 * alpha).toInt()
        detail.alpha = (215 * alpha).toInt()
        // The last line is the size when it is the item's detail; the lines before it are the name.
        val names = if (lines.size > 1 && lines.last() == items[index].detail) lines.size - 1 else lines.size
        var y = r.top + pad
        for (i in lines.indices) {
            val paint = if (i < names) title else detail
            if (i == names) y += 3 * density
            y -= paint.ascent()
            canvas.drawText(lines[i], r.left + pad, y, paint)
            y += paint.descent()
        }
    }

    private fun hit(x: Float, y: Float) = tiles.indexOfFirst { it.contains(x, y) }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = hit(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = index
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val tapped = index >= 0 && index == pressed
                pressed = -1
                invalidate()
                if (tapped) {
                    performClick()
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (index == selected) {
                        onOpen?.invoke(items[index])
                    } else {
                        selected = index
                        onSelect?.invoke(items[index])
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

    override fun performClick(): Boolean = super.performClick()

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        helper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    // TalkBack: one element per tile; activating it opens the folder, as a row would.
    private inner class Access : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int = hit(x, y).takeIf { it >= 0 } ?: INVALID_ID

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            ids += tiles.indices
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            node.contentDescription = items.getOrNull(id)?.description.orEmpty()
            node.className = android.widget.Button::class.java.name
            node.isClickable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            val r = tiles.getOrNull(id) ?: RectF(0f, 0f, 1f, 1f)
            @Suppress("DEPRECATION")
            node.setBoundsInParent(Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()))
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            items.getOrNull(id)?.let { onOpen?.invoke(it) }
            return true
        }
    }
}
