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
            animate().cancel()
            setColor(getItem(position))
            if (!Motion.any || revealed[position]) {
                scaleX = 1f
                scaleY = 1f
            } else {
                scaleX = 0.6f
                scaleY = 0.6f
                onFirstSight {
                    revealed[position] = true
                    val delay = (position * 25L).coerceAtMost(300L)
                    animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setStartDelay(delay)
                        .setDuration(250)
                        .setInterpolator(EMPHASIZED)
                        .start()
                    revealSelection(delay)
                }
            }
        }
    }

    companion object {
        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
