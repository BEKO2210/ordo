/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.settings

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.color.MaterialColors
import me.zhanghai.android.files.R
import me.zhanghai.android.files.launcher.IconColor
import me.zhanghai.android.files.launcher.LauncherIcons
import me.zhanghai.android.files.ui.Motion

/**
 * The background colour of one launcher icon: the icon itself as the launcher gets it, and eight round swatches.
 * The page reads the state from the system ([show]) and does the switching in [onChoose].
 */
class IconColorPreference(
    context: Context,
    val prefix: String,
    private val onChoose: (IconColor) -> Boolean
) : Preference(context) {
    private var color = IconColor.BLUE
    private var iconOn = true

    private var boundPreview: ImageView? = null
    private var boundSummary: TextView? = null
    private val boundSwatches = mutableListOf<Pair<View, AccentSwatchView>>()

    init {
        layoutResource = R.layout.icon_color_preference
        isSelectable = false
        isPersistent = false
    }

    /** [iconOn] false: a built-in app switched off, whose [color] is only remembered for when it comes on. */
    fun show(color: IconColor, iconOn: Boolean) {
        this.color = color
        this.iconOn = iconOn
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        boundSwatches.forEach { it.second.cancelAnimation() }
        boundSwatches.clear()

        // Preference greys the title of rows that are not selectable; this one is, through its swatches.
        context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).use { it.getColorStateList(0) }
            ?.let { (holder.findViewById(android.R.id.title) as TextView).setTextColor(it) }
        boundSummary = (holder.findViewById(android.R.id.summary) as TextView).also { showSummary(it) }
        boundPreview = (holder.findViewById(R.id.icon_preview) as ImageView).apply {
            animate().cancel()
            setImageDrawable(LauncherIcons.icon(context, prefix, color))
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
        }

        val grid = holder.findViewById(R.id.swatches) as GridLayout
        grid.removeAllViews()
        val cell = context.resources.getDimensionPixelSize(R.dimen.phi_55)
        val ringColor = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK
        )
        IconColor.entries.forEach { swatchColor ->
            val chosen = swatchColor == color
            val swatch = AccentSwatchView(context, ContextCompat.getColor(context, swatchColor.colorRes), ringColor)
                .apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = FrameLayout.LayoutParams(cell, cell, Gravity.CENTER)
                    setSelectedImmediately(chosen)
                }
            val target = FrameLayout(context).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = cell
                    height = cell
                }
                addView(swatch)
                // TalkBack: "Icon colour Green, selected" as a radio button of the group.
                contentDescription = context.getString(
                    R.string.launcher_color_description, context.getString(swatchColor.labelRes)
                )
                isSelected = chosen
                ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = RadioButton::class.java.name
                        info.isCheckable = true
                        info.isChecked = host.isSelected
                    }
                })
                background = TypedValue().let {
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
                    ContextCompat.getDrawable(context, it.resourceId)
                }
                setOnClickListener { choose(swatchColor, this) }
            }
            grid.addView(target)
            boundSwatches += target to swatch
        }
    }

    private fun choose(newColor: IconColor, hapticView: View) {
        if (newColor == color || !onChoose(newColor)) return
        hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        color = newColor
        boundSwatches.forEachIndexed { index, (target, swatch) ->
            val chosen = IconColor.entries[index] == newColor
            if (target.isSelected != chosen) {
                target.isSelected = chosen
                swatch.setSelected(chosen, Motion.any)
            }
        }
        boundSummary?.let { showSummary(it) }
        showNewIcon()
    }

    private fun showSummary(view: TextView) {
        val name = context.getString(color.labelRes)
        view.text = if (iconOn) name else context.getString(R.string.launcher_color_off_summary, name)
        view.visibility = View.VISIBLE
    }

    /** The old icon sinks away and the new one settles in, about a quarter second; without motion it just changes. */
    private fun showNewIcon() {
        val preview = boundPreview ?: return
        val icon = LauncherIcons.icon(context, prefix, color)
        preview.animate().cancel()
        if (!Motion.any) {
            preview.setImageDrawable(icon)
            return
        }
        preview.animate().scaleX(0.84f).scaleY(0.84f).alpha(0f).setDuration(90)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                preview.setImageDrawable(icon)
                preview.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setInterpolator(EMPHASIZED).start()
            }
            .start()
    }

    override fun onDetached() {
        boundPreview?.animate()?.cancel()
        boundSwatches.forEach { it.second.cancelAnimation() }
        boundSwatches.clear()
        boundPreview = null
        boundSummary = null
        super.onDetached()
    }

    companion object {
        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
