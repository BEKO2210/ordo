/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.widget.ImageView
import androidx.core.content.res.use
import androidx.core.graphics.ColorUtils
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import me.zhanghai.android.files.R

/** A row of the settings overview: white pictogram on a coloured circle, title, summary and a chevron. */
class SettingsCategoryPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {
    private val circle: GradientDrawable

    init {
        layoutResource = R.layout.settings_category_preference
        val (start, end) = context.obtainStyledAttributes(attrs, R.styleable.SettingsCategoryPreference).use {
            val start = it.getColor(R.styleable.SettingsCategoryPreference_ordoCircleColor, Color.GRAY)
            // Without an end colour, a slightly deeper shade gives the circle some depth.
            start to it.getColor(R.styleable.SettingsCategoryPreference_ordoCircleColorEnd,
                ColorUtils.blendARGB(start, Color.BLACK, 0.18f))
        }
        circle = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
            shape = GradientDrawable.OVAL
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(android.R.id.icon) as ImageView).apply {
            background = circle
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
    }
}
