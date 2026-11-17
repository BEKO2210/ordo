/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import androidx.core.graphics.ColorUtils
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
import me.zhanghai.android.files.util.getColorByAttr

/**
 * The background colour of one launcher icon: the icon itself as the launcher gets it, the chosen colour's name, and
 * eight round swatches across the row. The page reads the state from the system ([show]) and switches in [onChoose].
 */
class IconColorPreference(
    context: Context,
    val prefix: String,
    private val onChoose: (IconColor) -> Boolean
) : Preference(context) {
    private var color = IconColor.BLUE
    private var iconOn = true

    private var boundPreview: ImageView? = null
    private var boundName: TextView? = null
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
        (holder.findViewById(android.R.id.summary) as TextView).apply {
            setText(R.string.launcher_color_off_summary)
            visibility = if (iconOn) View.GONE else View.VISIBLE
        }
        boundName = (holder.findViewById(R.id.color_name) as TextView).also { showName(it) }
        boundPreview = (holder.findViewById(R.id.icon_preview) as ImageView).apply {
            animate().cancel()
            setImageDrawable(LauncherIcons.icon(context, prefix, color))
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
        }

        val grid = holder.findViewById(R.id.swatches) as LinearLayout
        grid.removeAllViews()
        val resources = context.resources
        val cellHeight = resources.getDimensionPixelSize(R.dimen.phi_55)
        val ringColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val page = context.getColorByAttr(android.R.attr.colorBackground)
        val outline = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutline, ringColor)
        // All eight in one line once each gets a 55 dp cell next to the page's side padding, otherwise two lines of four.
        val columns = if (resources.configuration.screenWidthDp - 2 * SIDE_DP >= IconColor.entries.size * 55) {
            IconColor.entries.size
        } else {
            IconColor.entries.size / 2
        }
        var line: LinearLayout? = null
        IconColor.entries.forEachIndexed { index, swatchColor ->
            if (index % columns == 0) {
                line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }.also { grid.addView(it) }
            }
            val chosen = swatchColor == color
            @ColorInt val fill = ContextCompat.getColor(context, swatchColor.colorRes)
            val swatch = AccentSwatchView(
                context, fill, ringColor, SWATCH_RADIUS_DP,
                // WCAG 1.4.11: a swatch below 3:1 against the page (graphite on dark) gets a visible edge.
                outline.takeIf { ColorUtils.calculateContrast(fill, page) < 3.0 }
            ).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = FrameLayout.LayoutParams(cellHeight, cellHeight, Gravity.CENTER)
                setSelectedImmediately(chosen)
            }
            val target = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, cellHeight, 1f)
                clipChildren = false
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
            line!!.addView(target)
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
        boundName?.let { name ->
            showName(name)
            if (Motion.any) {
                name.alpha = 0f
                name.animate().alpha(1f).setDuration(220).setInterpolator(EMPHASIZED).start()
            }
        }
        boundPreview?.swapIcon(LauncherIcons.icon(context, prefix, color))
    }

    private fun showName(view: TextView) {
        view.text = context.getString(color.labelRes)
        val size = context.resources.getDimensionPixelSize(R.dimen.phi_13)
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(context, this@IconColorPreference.color.colorRes))
            setSize(size, size)
        }
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
    }

    override fun onDetached() {
        boundPreview?.animate()?.cancel()
        boundName?.animate()?.cancel()
        boundSwatches.forEach { it.second.cancelAnimation() }
        boundSwatches.clear()
        boundPreview = null
        boundName = null
        super.onDetached()
    }

    companion object {
        private const val SIDE_DP = 16
        // A 40 dp circle; its 3 dp ring reaches 49 dp, inside the 55 dp cell.
        private const val SWATCH_RADIUS_DP = 20f
    }
}

private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)

/** The old icon sinks away and the new one settles in, about a quarter second; without motion it just changes. */
internal fun ImageView.swapIcon(icon: Drawable?) {
    animate().cancel()
    if (!Motion.any) {
        setImageDrawable(icon)
        return
    }
    animate().scaleX(0.84f).scaleY(0.84f).alpha(0f).setDuration(90)
        .setInterpolator(AccelerateInterpolator())
        .withEndAction {
            setImageDrawable(icon)
            animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setInterpolator(EMPHASIZED).start()
        }
        .start()
}
