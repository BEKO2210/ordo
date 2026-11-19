/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * Equal-width columns that each stack their cards without holes: the visible children go left, right, left… in
 * list order, and a short card no longer leaves a gap beside a tall one as a grid row would. Gaps come from the
 * children's margins, like in a GridLayout.
 */
class MasonryLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {
    var columnCount = 1
        set(value) {
            if (field == value) return
            field = value.coerceAtLeast(1)
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val columnWidth = (width - paddingLeft - paddingRight) / columnCount
        val heights = visibleChildren().map { child ->
            val params = child.layoutParams as MarginLayoutParams
            child.measure(
                MeasureSpec.makeMeasureSpec(
                    (columnWidth - params.leftMargin - params.rightMargin).coerceAtLeast(0), MeasureSpec.EXACTLY
                ),
                getChildMeasureSpec(heightMeasureSpec, 0, params.height)
            )
            child.measuredHeight + params.topMargin + params.bottomMargin
        }
        val height = columnTops(heights, columnCount).last().max() + paddingTop + paddingBottom
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val columnWidth = (r - l - paddingLeft - paddingRight) / columnCount
        val children = visibleChildren()
        val heights = children.map {
            val params = it.layoutParams as MarginLayoutParams
            it.measuredHeight + params.topMargin + params.bottomMargin
        }
        val tops = columnTops(heights, columnCount)
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        children.forEachIndexed { index, child ->
            val params = child.layoutParams as MarginLayoutParams
            val column = index % columnCount
            val visualColumn = if (rtl) columnCount - 1 - column else column
            val left = paddingLeft + visualColumn * columnWidth + params.leftMargin
            val top = paddingTop + tops[index][column] + params.topMargin
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    private fun visibleChildren(): List<View> =
        (0 until childCount).map { getChildAt(it) }.filter { it.visibility != View.GONE }

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams = MarginLayoutParams(context, attrs)

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(params: LayoutParams): LayoutParams = MarginLayoutParams(params)

    override fun checkLayoutParams(params: LayoutParams): Boolean = params is MarginLayoutParams

    companion object {
        /**
         * Column heights before each child (index i sits in column i % columns) and, as the last entry, after all of
         * them; heights include the margins.
         */
        fun columnTops(heights: List<Int>, columns: Int): List<IntArray> {
            val bottoms = IntArray(columns)
            val result = ArrayList<IntArray>(heights.size + 1)
            heights.forEachIndexed { index, height ->
                result += bottoms.copyOf()
                bottoms[index % columns] += height
            }
            result += bottoms
            return result
        }
    }
}
