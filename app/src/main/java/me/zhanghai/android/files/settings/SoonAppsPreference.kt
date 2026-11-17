/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.color.MaterialColors
import me.zhanghai.android.files.R
import me.zhanghai.android.files.launcher.BuiltInApp

/**
 * The built-in apps that have no icon yet, as one calm row of pictograms with their names: readable in full contrast
 * (nothing greyed out), with nothing to switch until an app is ready.
 */
class SoonAppsPreference(context: Context, private val apps: List<BuiltInApp>) : Preference(context) {
    init {
        layoutResource = R.layout.launcher_soon_apps
        isSelectable = false
        isPersistent = false
        setTitle(R.string.launcher_soon_title)
        setSummary(R.string.launcher_coming_soon)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        // Preference greys the title of rows that are not selectable; this one only shows.
        context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).use { it.getColorStateList(0) }
            ?.let { (holder.findViewById(android.R.id.title) as TextView).setTextColor(it) }
        val row = holder.findViewById(R.id.soon_apps) as LinearLayout
        row.removeAllViews()
        val container = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorSecondaryContainer, Color.LTGRAY
        )
        val onContainer = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorOnSecondaryContainer, Color.BLACK
        )
        val labelColor = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
            .use { it.getColorStateList(0) }
        val circle = context.resources.getDimensionPixelSize(R.dimen.phi_34)
        val glyph = context.resources.getDimensionPixelSize(R.dimen.phi_21)
        apps.forEach { app ->
            val icon = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(circle, circle)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(container)
                }
                addView(ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(glyph, glyph, Gravity.CENTER)
                    setImageDrawable(ContextCompat.getDrawable(context, app.iconRes)?.mutate())
                    setColorFilter(onContainer)
                })
            }
            val label = TextView(context).apply {
                setText(app.labelRes)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
                labelColor?.let { setTextColor(it) }
                setPadding(0, context.resources.getDimensionPixelSize(R.dimen.space_related), 0, 0)
            }
            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(icon)
                addView(label)
            })
        }
    }
}
