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
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
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

/** Ordo's accent colours as five round swatches; the chosen one carries a ring and a check mark. */
class AccentPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {
    private val values = context.resources.getStringArray(R.array.pref_entry_values_accent)
    private val names = context.resources.getStringArray(R.array.settings_accent_entries)
    private val colors = intArrayOf(
        R.color.accent_aurora_primary, R.color.accent_blue_primary, R.color.accent_mint_primary,
        R.color.accent_violet_primary, R.color.accent_amber_primary
    )

    init {
        layoutResource = R.layout.accent_preference
        isSelectable = false
    }

    private val selected: String
        get() = getPersistedString(context.getString(R.string.pref_default_value_accent))

    override fun onAttached() {
        super.onAttached()
        summaryProvider = SummaryProvider<AccentPreference> { names[values.indexOf(selected).coerceAtLeast(0)] }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        // Preference greys the title of rows that are not selectable; this one is, through its swatches.
        context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).use { it.getColorStateList(0) }
            ?.let { (holder.findViewById(android.R.id.title) as TextView).setTextColor(it) }
        val row = holder.findViewById(R.id.swatches) as LinearLayout
        row.removeAllViews()
        val density = context.resources.displayMetrics.density
        val ring = MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnSurface)
        values.forEachIndexed { i, value ->
            val color = ContextCompat.getColor(context, colors[i])
            val chosen = value == selected
            val swatch = ImageView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = FrameLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt(), Gravity.CENTER)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (chosen) setStroke((3 * density).toInt(), ring)
                }
                if (chosen) {
                    setImageResource(R.drawable.check_icon_control_normal_24dp)
                    // The check mark takes whichever of black or white reads better on the swatch.
                    imageTintList = ColorStateList.valueOf(
                        if (ColorUtils.calculateContrast(Color.WHITE, color) >= 3) Color.WHITE else Color.BLACK)
                    val pad = (7 * density).toInt()
                    setPadding(pad, pad, pad, pad)
                }
            }
            // 48dp touch target around the 36dp circle.
            row.addView(FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
                addView(swatch)
                contentDescription = names[i]
                isSelected = chosen
                // TalkBack reads the five swatches as radio buttons: name, then checked or not checked.
                ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = RadioButton::class.java.name
                        info.isCheckable = true
                        info.isChecked = chosen
                    }
                })
                isEnabled = this@AccentPreference.isEnabled
                alpha = if (this@AccentPreference.isEnabled) 1f else 0.38f
                background = TypedValue().let {
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
                    ContextCompat.getDrawable(context, it.resourceId)
                }
                setOnClickListener {
                    if (value == selected || !callChangeListener(value)) return@setOnClickListener
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    persistString(value)
                    notifyChanged()
                }
            })
        }
    }
}
