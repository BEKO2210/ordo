/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isEmpty
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.color.MaterialColors
import me.zhanghai.android.files.R
import me.zhanghai.android.files.launcher.IconColor
import me.zhanghai.android.files.launcher.LauncherIcons

/**
 * The page's picture: a small home screen with the real icons of Ordo and its built-in apps in the colours they have
 * now. A colour chosen below changes its icon here too, with the same calm swap as the row's preview.
 */
class LauncherIconsHeaderPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {
    data class Icon(val prefix: String, val label: String, val color: IconColor)

    private var icons = emptyList<Icon>()
    private var boundViews = emptyList<ImageView>()

    init {
        layoutResource = R.layout.launcher_icons_header
        isSelectable = false
        isPersistent = false
    }

    fun show(newIcons: List<Icon>) {
        val old = icons
        icons = newIcons
        if (boundViews.size != newIcons.size || old.map { it.prefix } != newIcons.map { it.prefix }) {
            notifyChanged()
            return
        }
        newIcons.forEachIndexed { index, icon ->
            if (old[index].color != icon.color) {
                boundViews[index].swapIcon(LauncherIcons.icon(context, icon.prefix, icon.color))
            }
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val screen = holder.findViewById(R.id.home_screen) as LinearLayout
        val radius = context.resources.getDimension(R.dimen.phi_radius_l)
        val surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        screen.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimaryContainer, surface),
                MaterialColors.getColor(context, com.google.android.material.R.attr.colorTertiaryContainer, surface)
            )
        ).apply { cornerRadius = radius }
        val labelColor = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK
        )
        val size = context.resources.getDimensionPixelSize(R.dimen.phi_55)
        val gap = context.resources.getDimensionPixelSize(R.dimen.phi_34)
        screen.removeAllViews()
        boundViews = icons.map { icon ->
            val image = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                setImageDrawable(LauncherIcons.icon(context, icon.prefix, icon.color))
            }
            val label = TextView(context).apply {
                text = icon.label
                setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
                setTextColor(labelColor)
                setPadding(0, context.resources.getDimensionPixelSize(R.dimen.space_related), 0, 0)
            }
            screen.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (!screen.isEmpty()) marginStart = gap }
                addView(image)
                addView(label)
            })
            image
        }
    }

    override fun onDetached() {
        boundViews.forEach { it.animate().cancel() }
        boundViews = emptyList()
        super.onDetached()
    }
}
