/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.colorpicker

import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.AbsListView
import android.widget.BaseAdapter
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.ui.onFirstSight

class ColorPaletteAdapter(private val colors: IntArray) : BaseAdapter() {
    private val revealed = BooleanArray(colors.size)

    override fun hasStableIds(): Boolean = true

    override fun getCount(): Int = colors.size

    override fun getItem(position: Int): Int = colors[position]

    override fun getItemId(position: Int): Long = getItem(position).toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val swatchView = convertView as ColorSwatchView?
            ?: ColorSwatchView(parent.context).apply {
                layoutParams = AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        return swatchView.apply {
            prepareForBinding()
            setColor(getItem(position))
            setSelectionImmediately(isChecked)
            if (!Motion.any || revealed[position]) {
                alpha = 1f
                scaleX = 1f
                scaleY = 1f
            } else {
                alpha = 0f
                scaleX = 0.85f
                scaleY = 0.85f
                onFirstSight {
                    revealed[position] = true
                    // Four quiet waves preserve the 25 ms rhythm without letting a 20-color palette
                    // keep moving long after the dialog has opened.
                    val delay = (position * 25L).coerceAtMost(MAX_REVEAL_DELAY_MS)
                    animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setStartDelay(delay)
                        .setDuration(REVEAL_BUDGET_MS - delay)
                        .setInterpolator(EMPHASIZED)
                        .start()
                    revealSelection(delay)
                }
            }
        }
    }

    companion object {
        private const val MAX_REVEAL_DELAY_MS = 75L
        private const val REVEAL_BUDGET_MS = 240L
        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
