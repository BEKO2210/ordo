/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.settings

import android.content.Context
import android.content.DialogInterface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.NestedScrollView
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.ui.MaterialPreferenceDialogFragmentCompat
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.ui.onFirstSight
import me.zhanghai.android.files.util.ParcelableState
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.getState
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putState
import me.zhanghai.android.files.util.withTheme

/**
 * The choice dialog of a [VisualListPreference]: every option is a row with its own pictogram, label and a check.
 * Saves the chosen value as soon as a row is tapped.
 */
class VisualListPreferenceDialogFragment : MaterialPreferenceDialogFragmentCompat() {
    override val preference: VisualListPreference
        get() = super.preference as VisualListPreference

    private lateinit var entries: Array<CharSequence>
    private lateinit var entryValues: Array<CharSequence>
    private var checkedEntryIndex = 0
    private var restored = false

    private val rows = mutableListOf<Row>()
    private var chosen = false
    private var closeRunnable: Runnable? = null

    private class Row(
        val root: View,
        val outline: View,
        val check: View,
        val pictogram: ChoicePictogramDrawable
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            val preference = preference
            entries = preference.entries
            entryValues = preference.entryValues
            checkedEntryIndex = preference.findIndexOfValue(
                preference.value ?: preference.entryValues.firstOrNull()?.toString()
            )
        } else {
            val state = savedInstanceState.getState<State>()
            entries = state.entries
            entryValues = state.entryValues
            checkedEntryIndex = state.checkedEntryIndex
            chosen = state.chosen
            restored = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putState(State(entries, entryValues, checkedEntryIndex, chosen))
    }

    override fun onStart() {
        super.onStart()

        // A choice that recreates the activity (night mode) lands before the delayed close; don't come back open.
        if (restored && chosen) dismissAllowingStateLoss()
    }

    override fun onCreateDialogView(context: Context): View? = context.withTheme(theme).let { themed ->
        NestedScrollView(themed).apply {
            addView(
                LinearLayout(themed).apply {
                    orientation = LinearLayout.VERTICAL
                    val padding = themed.resources.getDimensionPixelSize(R.dimen.phi_8)
                    setPadding(0, padding, 0, padding)
                }
            )
        }
    }

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)

        val context = view.context
        val list = (view as NestedScrollView).getChildAt(0) as LinearLayout
        val style = preference.style
        rows.clear()
        entries.forEachIndexed { index, entry ->
            val root = context.layoutInflater.inflate(R.layout.visual_choice_item, list, false)
            val outline = root.findViewById<View>(R.id.choice_outline)
            val check = root.findViewById<View>(R.id.choice_check)
            val pictogram = ChoicePictogramDrawable(
                context, VisualListPreference.kindFor(style, entryValues[index].toString(), false)
            )
            root.findViewById<ImageView>(R.id.choice_pictogram).setImageDrawable(pictogram)
            root.findViewById<TextView>(R.id.choice_label).text = entry
            outline.background = GradientDrawable().apply {
                cornerRadius = context.resources.getDimension(R.dimen.phi_radius_l)
                setStroke(
                    context.resources.getDimensionPixelSize(R.dimen.phi_3),
                    context.getColorByAttr(androidx.appcompat.R.attr.colorPrimary)
                )
            }
            val selected = index == checkedEntryIndex
            root.isSelected = selected
            outline.alpha = if (selected) 1f else 0f
            check.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            // TalkBack reads each row as a radio button: label, then selected or not.
            ViewCompat.setAccessibilityDelegate(root, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.text = entry
                    info.className = RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = host.isSelected
                }
            })
            root.contentDescription = entry
            root.setOnClickListener { choose(index) }
            list.addView(root)
            rows += Row(root, outline, check, pictogram)
            if (!restored && Motion.any) enter(root, index)
        }
    }

    // Rows settle in one after the other: 30 ms apart, a short rise, the emphasized curve.
    private fun enter(root: View, index: Int) {
        root.alpha = 0f
        root.translationY = root.resources.getDimension(R.dimen.phi_8)
        root.onFirstSight {
            root.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * 30L)
                .setDuration(260)
                .setInterpolator(EMPHASIZED)
                .start()
        }
    }

    private fun choose(index: Int) {
        if (chosen) return
        chosen = true
        val previous = checkedEntryIndex
        checkedEntryIndex = index
        val animate = Motion.any
        if (previous != index) {
            rows.getOrNull(previous)?.let { setSelected(it, false, animate) }
        }
        val row = rows[index]
        if (previous != index) setSelected(row, true, animate)
        row.root.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.CLOCK_TICK
        )
        row.pictogram.play()
        // Saved at once: closing early, back or rotation must never lose the choice.
        val value = entryValues[index].toString()
        val preference = preference
        if (preference.callChangeListener(value)) {
            preference.value = value
        }
        val dialog = dialog ?: return
        // The picture plays a moment before the dialog leaves; the dialog's own exit is the faster half.
        if (animate) {
            val close = Runnable { dialog.dismiss() }
            closeRunnable = close
            row.root.postDelayed(close, CLOSE_DELAY)
        } else {
            dialog.dismiss()
        }
    }

    private fun cancelPending() {
        closeRunnable?.let { close -> rows.forEach { it.root.removeCallbacks(close) } }
        closeRunnable = null
        rows.forEach {
            it.pictogram.stop()
            it.outline.animate().cancel()
            it.check.animate().cancel()
            it.root.animate().cancel()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        cancelPending()
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        cancelPending()
        rows.clear()
        super.onDestroyView()
    }

    private fun setSelected(row: Row, selected: Boolean, animate: Boolean) {
        row.root.isSelected = selected
        val outlineTarget = if (selected) 1f else 0f
        if (!animate) {
            row.outline.alpha = outlineTarget
            row.check.apply {
                alpha = 1f
                scaleX = 1f
                scaleY = 1f
                visibility = if (selected) View.VISIBLE else View.INVISIBLE
            }
            return
        }
        row.outline.animate().alpha(outlineTarget).setDuration(CHECK_DURATION).setInterpolator(EMPHASIZED).start()
        row.check.animate().cancel()
        if (selected) {
            row.check.apply {
                alpha = 0f
                scaleX = 0.8f
                scaleY = 0.8f
                visibility = View.VISIBLE
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(CHECK_DURATION).setInterpolator(EMPHASIZED)
                    .start()
            }
        } else {
            row.check.animate().alpha(0f).setDuration(CHECK_DURATION * 2 / 3).setInterpolator(ACCELERATE)
                .withEndAction {
                    row.check.visibility = View.INVISIBLE
                    row.check.alpha = 1f
                }
                .start()
        }
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)

        builder.setPositiveButton(null, null)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        // The choice is saved in choose().
    }

    @Parcelize
    private class State(
        val entries: Array<CharSequence>,
        val entryValues: Array<CharSequence>,
        val checkedEntryIndex: Int,
        val chosen: Boolean
    ) : ParcelableState

    private companion object {
        const val CHECK_DURATION = 140L
        const val CLOSE_DELAY = 260L
        val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)
        val ACCELERATE = AccelerateInterpolator()
    }
}
