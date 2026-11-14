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
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import me.zhanghai.android.files.R
import me.zhanghai.android.files.ui.onFirstSight
import kotlin.math.max
import kotlin.math.min

/**
 * Folders as tiles sized by bytes (squarified layout). One hue, darker for bigger, as magnitude wants; labels only
 * where they fit. Tapping a tile reports its item.
 */
class TreemapView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    class Item(val label: String, val detail: String, val value: Long, val key: Any?)

    var onItemClick: ((Item) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val gap = 2 * density
    private val radius = 10 * density
    private val hue = ContextCompat.getColor(context, R.color.ai_accent)
    private val surface by lazy {
        com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        // sp, so the labels follow the user's font size.
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
        isFakeBoldText = true
    }
    private val detail = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }
    private var items: List<Item> = emptyList()
    private var tiles: List<Pair<Item, RectF>> = emptyList()
    private var progress = 1f
    private val tmp = RectF()
    private var pressed = -1
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 255, 255, 255) }

    init {
        isClickable = true
    }

    fun setItems(items: List<Item>) {
        this.items = items.filter { it.value > 0 }.sortedByDescending { it.value }
        layoutTiles()
        contentDescription = this.items.joinToString { "${it.label}: ${it.detail}" }
        if (me.zhanghai.android.files.ui.Motion.any) {
            progress = 0f
            invalidate()
            onFirstSight {
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 900
                    startDelay = 200
                    interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
                    addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                }.start()
            }
        } else {
            progress = 1f
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutTiles()

    private fun layoutTiles() {
        if (width == 0 || items.isEmpty()) {
            tiles = emptyList()
            return
        }
        val total = items.sumOf { it.value }.toDouble()
        val area = width.toDouble() * height
        val out = ArrayList<Pair<Item, RectF>>()
        squarify(items.map { it to it.value / total * area }, RectF(0f, 0f, width.toFloat(), height.toFloat()), out)
        tiles = out
    }

    // Bruls et al.: add items to a row while the worst aspect ratio improves, then lay the row along the short side.
    private fun squarify(rest: List<Pair<Item, Double>>, box: RectF, out: MutableList<Pair<Item, RectF>>) {
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
        for ((item, a) in row) {
            val length = (a / thickness).toFloat()
            out += item to if (horizontal) {
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

    private fun worst(row: List<Pair<Item, Double>>, side: Double): Double {
        val sum = row.sumOf { it.second }
        val maxA = row.maxOf { it.second }
        val minA = row.minOf { it.second }
        return max(side * side * maxA / (sum * sum), sum * sum / (side * side * minA))
    }

    override fun onDraw(canvas: Canvas) {
        val biggest = items.firstOrNull()?.value?.toFloat() ?: return
        tiles.forEachIndexed { i, (item, r) ->
            val local = ((progress * (tiles.size + 3) - i) / 4f).coerceIn(0f, 1f)
            if (local <= 0f) return@forEachIndexed
            // Sequential: share of the biggest folder sets how much accent goes into the surface colour.
            val strength = 0.28f + 0.52f * (item.value / biggest)
            val color = ColorUtils.blendARGB(surface, hue, strength)
            fill.color = color
            fill.alpha = (255 * local).toInt()
            val inset = gap / 2 + (1 - local) * 8 * density
            tmp.set(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
            canvas.drawRoundRect(tmp, radius, radius, fill)
            drawLabels(canvas, item, tmp, color, local)
            // Touch feedback: the tile under the finger lights up.
            if (i == pressed) canvas.drawRoundRect(tmp, radius, radius, pressPaint)
        }
    }

    private fun drawLabels(canvas: Canvas, item: Item, r: RectF, background: Int, alpha: Float) {
        val pad = 10 * density
        if (r.width() < 56 * density || r.height() < 36 * density) return
        // Text colour by contrast with its own tile, never the series colour.
        val ink = if (ColorUtils.calculateContrast(Color.WHITE, background or 0xFF000000.toInt()) >= 4.5) Color.WHITE
        else Color.argb(255, 15, 23, 42)
        title.color = ink
        detail.color = ColorUtils.setAlphaComponent(ink, 200)
        title.alpha = (255 * alpha).toInt()
        detail.alpha = (200 * alpha).toInt()
        val width = r.width() - 2 * pad
        canvas.drawText(TextUtils.ellipsize(item.label, title, width, TextUtils.TruncateAt.END).toString(),
            r.left + pad, r.top + pad + title.textSize, title)
        if (r.height() >= 54 * density) {
            canvas.drawText(TextUtils.ellipsize(item.detail, detail, width, TextUtils.TruncateAt.END).toString(),
                r.left + pad, r.top + pad + title.textSize + 6 * density + detail.textSize, detail)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = tiles.indexOfFirst { it.second.contains(event.x, event.y) }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pressed = index
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val hit = index >= 0 && index == pressed
                pressed = -1
                invalidate()
                if (hit) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    performClick()
                    onItemClick?.invoke(tiles[index].first)
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
}
