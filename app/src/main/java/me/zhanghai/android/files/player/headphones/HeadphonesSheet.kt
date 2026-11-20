/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.headphones

import android.app.Dialog
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.HeadphonesSheetBinding
import me.zhanghai.android.files.databinding.KlangSwitchRowBinding
import me.zhanghai.android.files.player.AudioSettings
import me.zhanghai.android.files.ui.Motion

/** The sound sheet that opens [HeadphonesSheet] (Ordo Musik's or Ordo Video's): it owns and stores the settings. */
interface HeadphonesHost {
    val headphoneSettings: AudioSettings
    fun updateHeadphoneSettings(transform: AudioSettings.() -> AudioSettings)

    /** The song plays bit-perfect: the pack is bypassed and shows dimmed (Ordo Video never is). */
    val stagesBypassed: Boolean get() = false
}

/**
 * Ordo's headphone pack for music and video: virtual surround, "full sound when quiet" with its calibration, and the
 * AutoEq headphone correction with a search over the shipped models. No sliders: switches, segments, a list.
 */
class HeadphonesSheet : BottomSheetDialogFragment() {
    private var _binding: HeadphonesSheetBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val host get() = parentFragment as? HeadphonesHost
    private var rendering = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = BottomSheetDialog(requireContext(), theme)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = HeadphonesSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (host == null) return dismiss()
        bindRow(binding.surroundRow, R.drawable.audio_spatial_icon_24dp, R.string.headphones_virtual_surround,
            R.string.headphones_virtual_surround_summary) { copy(binauralEnabled = it) }
        bindRow(binding.quietRow, R.drawable.audio_volume_icon_24dp, R.string.audio_sound_quiet,
            R.string.audio_sound_quiet_summary) { copy(quietFullEnabled = it) }
        binding.calibrationGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked || rendering) return@addOnButtonCheckedListener
            val db = when (id) {
                R.id.calibrationQuieter -> -AudioSettings.QUIET_CALIBRATION_STEP_DB
                R.id.calibrationLouder -> AudioSettings.QUIET_CALIBRATION_STEP_DB
                else -> 0
            }
            update { copy(quietCalibrationDb = db) }
        }
        binding.correctionOff.setOnClickListener { update { copy(headphoneEq = "") } }
        binding.correctionSearch.doAfterTextChanged { showResults() }
        render()
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        if (!Motion.any) dialog.window?.setWindowAnimations(0)
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        BottomSheetBehavior.from(sheet).apply {
            maxWidth = resources.getDimensionPixelSize(R.dimen.klang_sheet_max_width)
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun bindRow(
        row: KlangSwitchRowBinding,
        icon: Int,
        title: Int,
        summary: Int,
        transform: AudioSettings.(Boolean) -> AudioSettings
    ) {
        row.icon.setImageResource(icon)
        row.title.setText(title)
        row.summary.setText(summary)
        row.toggle.contentDescription = getString(title)
        row.toggle.setOnCheckedChangeListener { _, checked -> if (!rendering) update { transform(checked) } }
        row.root.setOnClickListener { row.toggle.toggle() }
    }

    private fun update(transform: AudioSettings.() -> AudioSettings) {
        host?.updateHeadphoneSettings(transform)
        render()
    }

    /** The host's bypass state changed. */
    fun refresh() {
        if (_binding != null) render()
    }

    private fun render() {
        val settings = host?.headphoneSettings ?: return
        rendering = true
        binding.surroundRow.toggle.isChecked = settings.binauralEnabled
        binding.quietRow.toggle.isChecked = settings.quietFullEnabled
        binding.calibrationGroup.check(
            when {
                settings.quietCalibrationDb < 0 -> R.id.calibrationQuieter
                settings.quietCalibrationDb > 0 -> R.id.calibrationLouder
                else -> R.id.calibrationNormal
            }
        )
        val chosen = settings.headphoneEq
        binding.correctionState.text = if (chosen.isEmpty()) getString(R.string.headphones_correction_summary)
        else getString(R.string.headphones_correction_state, chosen)
        binding.correctionOff.isVisible = chosen.isNotEmpty()
        showResults()
        // After the result rows exist, so they are dimmed too.
        val bypassed = host?.stagesBypassed == true
        binding.bypassNote.isVisible = bypassed
        listOf(binding.surroundCard, binding.quietCard, binding.correctionCard).forEach {
            setEnabledRecursively(it, !bypassed)
            it.alpha = if (bypassed) BYPASSED_ALPHA else 1f
        }
        rendering = false
    }

    /** Up to [MAX_RESULTS] matches as 48 dp rows; an empty search shows none (500 rows would only be scrolled past). */
    private fun showResults() {
        val query = binding.correctionSearch.text?.toString().orEmpty()
        val results = binding.correctionResults
        results.removeAllViews()
        if (query.isBlank()) {
            binding.correctionCount.text = getString(R.string.headphones_correction_count, AutoEqPresets.all.size)
            return
        }
        val matches = AutoEqPresets.search(query)
        binding.correctionCount.text = when {
            matches.isEmpty() -> getString(R.string.headphones_correction_none)
            matches.size > MAX_RESULTS -> getString(R.string.headphones_correction_more, MAX_RESULTS, matches.size)
            else -> getString(R.string.headphones_correction_count, matches.size)
        }
        val chosen = host?.headphoneSettings?.headphoneEq
        matches.take(MAX_RESULTS).forEach { profile -> results.addView(row(profile, profile.name == chosen)) }
    }

    private fun row(profile: AutoEqProfile, selected: Boolean): View {
        val context = requireContext()
        val padding = resources.getDimensionPixelSize(R.dimen.klang_space_sm)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = resources.getDimensionPixelSize(R.dimen.klang_touch_target)
            setPadding(0, padding, 0, padding)
            isClickable = true
            isFocusable = true
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            addView(TextView(context, null, 0, R.style.Widget_Ordo_Klang_Title).apply {
                text = profile.name
                if (selected) {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.check_icon_control_normal_24dp, 0)
                }
            })
            addView(TextView(context, null, 0, R.style.Widget_Ordo_Klang_Info).apply {
                text = getString(R.string.headphones_correction_source, profile.source)
            })
            // Name, selection and who measured it, read as one item.
            contentDescription = (if (selected) getString(R.string.headphones_correction_selected, profile.name)
            else profile.name) + ", " + getString(R.string.headphones_correction_source, profile.source)
            isSelected = selected
            setOnClickListener { update { copy(headphoneEq = profile.name) } }
        }
    }

    private fun setEnabledRecursively(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) setEnabledRecursively(view.getChildAt(index), enabled)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val MAX_RESULTS = 30
        /** Like the Klang sheet's dimmed stages: graphic and text keep at least 3 : 1 against the card. */
        private const val BYPASSED_ALPHA = 0.6f
        private const val TAG = "headphones"

        /**
         * The entry row in a sound sheet: what is on, and a tap opens the pack. [fragment] must be a [HeadphonesHost].
         */
        fun bindEntry(row: KlangSwitchRowBinding, fragment: Fragment) {
            row.icon.setImageResource(R.drawable.audio_headphones_icon_24dp)
            row.title.setText(R.string.headphones_title)
            row.toggle.isVisible = false
            // A chevron where the switch would be: the row opens its own sheet (it read as plain text without).
            val context = row.root.context
            row.root.addView(android.widget.ImageView(context).apply {
                setImageResource(R.drawable.arrow_end_icon_white_24dp)
                imageTintList = com.google.android.material.color.MaterialColors.getColorStateListOrNull(
                    context, com.google.android.material.R.attr.colorOnSurfaceVariant
                )
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = android.widget.ImageView.ScaleType.CENTER
                val size = context.resources.getDimensionPixelSize(R.dimen.klang_touch_target)
                layoutParams = android.widget.LinearLayout.LayoutParams(size, size)
            })
            row.root.isClickable = true
            row.root.isFocusable = true
            row.root.contentDescription = null
            row.root.setOnClickListener {
                if (fragment.childFragmentManager.findFragmentByTag(TAG) == null) {
                    HeadphonesSheet().show(fragment.childFragmentManager, TAG)
                }
            }
            renderEntry(row, (fragment as HeadphonesHost).headphoneSettings)
        }

        /** [quietBassDb]: the lift "full sound when quiet" gives right now, where the player reports it. */
        fun renderEntry(row: KlangSwitchRowBinding, settings: AudioSettings, quietBassDb: Float? = null) {
            val context = row.root.context
            val quiet = context.getString(R.string.audio_sound_quiet).let { name ->
                if (quietBassDb == null) name
                else "$name (${context.getString(R.string.audio_sound_quiet_value, Math.round(quietBassDb))})"
            }
            val on = listOfNotNull(
                context.getString(R.string.headphones_virtual_surround).takeIf { settings.binauralEnabled },
                quiet.takeIf { settings.quietFullEnabled },
                settings.headphoneEq.takeIf { it.isNotEmpty() }
                    ?.let { context.getString(R.string.headphones_correction_state, it) }
            )
            row.summary.text = if (on.isEmpty()) context.getString(R.string.headphones_entry_summary)
            else on.joinToString(" · ")
            row.root.contentDescription = "${context.getString(R.string.headphones_title)}, ${row.summary.text}. " +
                context.getString(R.string.headphones_open)
        }
    }
}
