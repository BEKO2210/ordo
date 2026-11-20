/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.sound

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.KlangSwitchRowBinding
import me.zhanghai.android.files.databinding.VideoSoundSheetBinding
import me.zhanghai.android.files.player.EqualizerPreset
import me.zhanghai.android.files.player.LoudnessTarget
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.viewer.audio.KlangMath

/** The player screen that owns the sound; the sheet reads and writes through it. */
interface VideoSoundHost {
    val videoSound: VideoSoundEngine?
}

/**
 * Ordo Video › Sound: equalizer curve and presets, dialog enhancer, night mode, bass, mono and balance, in Ordo
 * Musik's Klang look. Every change is stored at once ([VideoSoundStore]) and heard at once.
 */
class VideoSoundSheet : BottomSheetDialogFragment() {
    private var _binding: VideoSoundSheetBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val engine get() = (activity as? VideoSoundHost)?.videoSound
    private var settings = VideoSoundSettings()
    private var rendering = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    // The measured and reached dialog distance move with the film: shown fresh once a second while the sheet is open.
    private val ticker = object : Runnable {
        override fun run() {
            if (_binding != null) renderDialogState()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = BottomSheetDialog(requireContext(), theme)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = VideoSoundSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val engine = engine ?: return dismiss()
        settings = engine.settings
        applyPalette()
        binding.close.setOnClickListener { dismiss() }
        binding.equalizerEnabled.setOnCheckedChangeListener { _, checked ->
            update { copy(klang = klang.copy(equalizerEnabled = checked)) }
        }
        binding.equalizerCurve.onGainChanged = { index, gain ->
            val bands = settings.klang.bandGainsDb.toMutableList().apply { this[index] = gain }
            update { copy(klang = klang.copy(preset = EqualizerPreset.CUSTOM, bandGainsDb = bands)) }
        }
        binding.equalizerReset.setOnClickListener { setPreset(EqualizerPreset.FLAT) }
        EqualizerPreset.entries.forEach { preset ->
            binding.presets.addView(Chip(requireContext()).apply {
                id = View.generateViewId()
                tag = preset
                text = getString(presetLabel(preset))
                isCheckable = true
                setOnClickListener { setPreset(preset) }
            })
        }
        bindRow(binding.dialogRow, R.drawable.audio_clarity_icon_24dp, R.string.video_sound_dialog,
            R.string.video_sound_dialog_summary) { checked ->
            copy(dialogLevel = if (checked) VideoSoundMath.DIALOG_LIGHT else VideoSoundMath.DIALOG_OFF)
        }
        binding.dialogGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) update {
                copy(dialogLevel = if (id == R.id.dialogStrong) VideoSoundMath.DIALOG_STRONG else VideoSoundMath.DIALOG_LIGHT)
            }
        }
        bindRow(binding.nightRow, R.drawable.audio_moon_icon_24dp, R.string.video_sound_night,
            R.string.video_sound_night_summary) { copy(nightMode = it) }
        bindRow(binding.levelRow, R.drawable.audio_graphic_eq_icon_24dp, R.string.video_sound_level,
            R.string.video_sound_level_summary) { copy(klang = klang.copy(normalizationEnabled = it)) }
        binding.levelTargetGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) update {
                copy(
                    klang = klang.copy(
                        loudnessTarget = if (id == R.id.levelTargetOnTheGo) LoudnessTarget.ON_THE_GO
                        else LoudnessTarget.ORIGINAL
                    )
                )
            }
        }
        binding.understand.setOnClickListener {
            if (rendering) return@setOnClickListener
            engine.setUnderstandBetter(!engine.understandBetter)
            settings = engine.settings
            render()
        }
        bindRow(binding.bassRow, R.drawable.audio_speaker_icon_24dp, R.string.audio_sound_bass_boost,
            R.string.video_sound_bass_summary) { checked ->
            val strength = if (klang.bassBoostStrength > 0) klang.bassBoostStrength else VideoSoundMath.bassStrength(2)
            copy(klang = klang.copy(bassBoostEnabled = checked, bassBoostStrength = strength))
        }
        binding.bassGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val level = when (id) {
                R.id.bassLight -> 1
                R.id.bassStrong -> 3
                else -> 2
            }
            update { copy(klang = klang.copy(bassBoostStrength = VideoSoundMath.bassStrength(level))) }
        }
        bindRow(binding.monoRow, R.drawable.audio_hearing_icon_24dp, R.string.audio_sound_mono,
            R.string.audio_sound_mono_summary) { copy(klang = klang.copy(mono = it)) }
        binding.balanceStepper.stepMinus.contentDescription = getString(R.string.video_sound_balance_left_button)
        binding.balanceStepper.stepPlus.contentDescription = getString(R.string.video_sound_balance_right_button)
        binding.balanceStepper.stepMinus.setOnClickListener {
            update { copy(klang = klang.copy(balance = (klang.balance - BALANCE_STEP).coerceAtLeast(-100))) }
        }
        binding.balanceStepper.stepPlus.setOnClickListener {
            update { copy(klang = klang.copy(balance = (klang.balance + BALANCE_STEP).coerceAtMost(100))) }
        }
        // A tap on the value re-centres.
        binding.balanceStepper.stepValue.setOnClickListener { update { copy(klang = klang.copy(balance = 0)) } }
        setColumns()
        // The audio session (and with it bass and balance), the track and the measured level can arrive later.
        engine.onChanged = { if (_binding != null) render() }
        render()
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        if (!Motion.any) dialog.window?.setWindowAnimations(0)
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val width = minOf(resources.displayMetrics.widthPixels, resources.getDimensionPixelSize(R.dimen.klang_sheet_max_width))
        sheet.layoutParams = sheet.layoutParams.apply { this.width = width }
        (sheet.layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.CENTER_HORIZONTAL
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_MS)
        BottomSheetBehavior.from(sheet).apply {
            maxWidth = width
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun bindRow(
        row: KlangSwitchRowBinding,
        icon: Int,
        title: Int,
        summary: Int,
        transform: VideoSoundSettings.(Boolean) -> VideoSoundSettings
    ) {
        row.icon.setImageResource(icon)
        row.title.setText(title)
        row.summary.setText(summary)
        row.toggle.contentDescription = getString(title)
        row.toggle.setOnCheckedChangeListener { _, checked -> update { transform(checked) } }
        row.root.setOnClickListener { if (row.toggle.isEnabled) row.toggle.toggle() }
    }

    private fun setPreset(preset: EqualizerPreset) {
        val gains = if (preset == EqualizerPreset.CUSTOM) settings.klang.bandGainsDb else preset.gainsDb
        update { copy(klang = klang.copy(equalizerEnabled = true, preset = preset, bandGainsDb = gains)) }
    }

    private fun update(transform: VideoSoundSettings.() -> VideoSoundSettings) {
        if (rendering) return
        val engine = engine ?: return
        engine.update(settings.transform())
        settings = engine.settings
        render()
    }

    private fun render() {
        rendering = true
        val klang = settings.klang
        val status = engine?.status
        binding.equalizerEnabled.isChecked = klang.equalizerEnabled
        binding.equalizerCurve.gainsDb = klang.bandGainsDb
        binding.equalizerReset.isInvisible = KlangMath.isFlat(klang.bandGainsDb)
        val eqAvailable = status?.equalizerAvailable != false
        binding.equalizerStage.alpha = if (klang.equalizerEnabled && eqAvailable) 1f else OFF_ALPHA
        binding.equalizerEnabled.isEnabled = eqAvailable
        for (index in 0 until binding.presets.childCount) {
            val chip = binding.presets.getChildAt(index) as Chip
            val preset = chip.tag as EqualizerPreset
            chip.isChecked = preset == klang.preset
            // "Custom" only appears once the curve was shaped by hand.
            if (preset == EqualizerPreset.CUSTOM) chip.isVisible = klang.preset == EqualizerPreset.CUSTOM
        }
        val dialogOn = settings.dialogLevel != VideoSoundMath.DIALOG_OFF
        binding.dialogRow.toggle.isChecked = dialogOn
        binding.dialogGroup.check(
            if (settings.dialogLevel == VideoSoundMath.DIALOG_STRONG) R.id.dialogStrong else R.id.dialogLight
        )
        setActive(binding.dialogGroup, dialogOn)
        binding.nightRow.toggle.isChecked = settings.nightMode
        binding.bassRow.toggle.isChecked = klang.bassBoostEnabled
        binding.bassGroup.check(
            when (VideoSoundMath.bassLevel(klang.bassBoostStrength)) {
                1 -> R.id.bassLight
                3 -> R.id.bassStrong
                else -> R.id.bassMedium
            }
        )
        setActive(binding.bassGroup, klang.bassBoostEnabled)
        // The device decides on bass (BassBoost) and balance (per-channel gain in DynamicsProcessing, API 28+).
        binding.bassCard.isVisible = status?.bassBoostAvailable != false
        binding.monoRow.toggle.isChecked = klang.mono
        binding.levelRow.toggle.isChecked = klang.normalizationEnabled
        binding.levelTargetGroup.check(
            if (klang.loudnessTarget == LoudnessTarget.ON_THE_GO) R.id.levelTargetOnTheGo else R.id.levelTargetOriginal
        )
        setActive(binding.levelTargetGroup, klang.normalizationEnabled)
        val number = java.text.NumberFormat.getInstance().apply { maximumFractionDigits = 1; minimumFractionDigits = 1 }
        val filmLufs = engine?.filmLufs
        binding.levelRow.summary.text = if (klang.normalizationEnabled && filmLufs != null) {
            getString(
                R.string.video_sound_level_measured, number.format(klang.loudnessTarget.lufs.toDouble()),
                number.format(filmLufs.toDouble())
            )
        } else {
            getString(R.string.video_sound_level_summary)
        }
        val understand = engine?.understandBetter == true
        binding.understand.isChecked = understand
        ViewCompat.setStateDescription(
            binding.understand, getString(if (understand) R.string.audio_sound_on else R.string.audio_sound_off)
        )
        renderDialogState()
        val balance = when {
            klang.balance < 0 -> getString(R.string.audio_sound_balance_left, -klang.balance)
            klang.balance > 0 -> getString(R.string.audio_sound_balance_right, klang.balance)
            else -> getString(R.string.audio_sound_balance_center)
        }
        binding.balanceStepper.stepValue.text = balance
        ViewCompat.setStateDescription(binding.balanceStepper.stepValue, balance)
        binding.balanceStepper.stepValue.contentDescription = getString(R.string.audio_sound_balance)
        setActive(binding.balanceCard, status?.usingDynamicsProcessing != false)
        val channels = engine?.sourceChannelCount ?: -1
        binding.surroundNote.isVisible = channels > 2
        if (channels > 2) {
            val layout = VideoSoundMath.channelLayout(channels) ?: getString(R.string.video_audio_track_channels, channels)
            binding.surroundNote.text = getString(
                when {
                    engine?.systemSpatializes(channels) == true -> R.string.video_sound_spatial_format
                    engine?.spatialHeldBack == true -> R.string.video_sound_spatial_held_back_format
                    else -> R.string.video_sound_surround_format
                },
                layout
            )
        }
        rendering = false
    }

    /** The measured and reached dialog distance (they move with the film; the ticker refreshes only this line). */
    private fun renderDialogState() {
        val number = java.text.NumberFormat.getInstance().apply { maximumFractionDigits = 1; minimumFractionDigits = 1 }
        val reached = engine?.dialogReachedLu ?: Float.NaN
        val measured = engine?.dialogDistanceLu ?: Float.NaN
        binding.dialogState.isVisible = settings.dialogLevel != VideoSoundMath.DIALOG_OFF && !reached.isNaN()
        if (binding.dialogState.isVisible) {
            binding.dialogState.text = getString(
                R.string.video_sound_dialog_state, number.format(reached.toDouble()), number.format(measured.toDouble())
            )
        }
    }

    private fun setActive(view: View, active: Boolean) {
        fun walk(view: View) {
            view.isEnabled = active
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(view)
        view.alpha = if (active) 1f else OFF_ALPHA
    }

    /** Two card columns only when each card gets at least the music sheet's minimum width (tablets). */
    private fun setColumns() {
        val density = resources.displayMetrics.density
        val sheetDp = minOf(
            resources.configuration.screenWidthDp.toFloat(),
            resources.getDimension(R.dimen.klang_sheet_max_width) / density
        )
        val contentDp = sheetDp - 2 * resources.getDimension(R.dimen.klang_space_lg) / density
        binding.effectsGrid.columnCount = KlangMath.columns(
            contentDp, resources.getDimension(R.dimen.klang_card_gap) / density,
            resources.getDimension(R.dimen.klang_column_min_width) / density
        )
    }

    // The music sheet's surface roles, with a computed layer where the theme family has none.
    private fun applyPalette() {
        val view = binding.root
        val surface = MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF.toInt())
        val onSurface = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface, 0xFF1C1B1F.toInt())
        val stage = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorSurfaceContainerHighest, MaterialColors.layer(surface, onSurface, 0.11f)
        )
        val card = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorSurfaceContainerHigh, MaterialColors.layer(surface, onSurface, 0.08f)
        )
        binding.equalizerStage.setCardBackgroundColor(stage)
        binding.understandCard.setCardBackgroundColor(card)
        binding.equalizerCurve.ringColor = stage
        for (index in 0 until binding.effectsGrid.childCount) {
            (binding.effectsGrid.getChildAt(index) as? MaterialCardView)?.setCardBackgroundColor(card)
        }
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onDestroyView() {
        engine?.onChanged = null
        binding.equalizerCurve.onGainChanged = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "video_sound"
        private const val BALANCE_STEP = 10
        private const val TICK_MS = 1_000L
        private const val OFF_ALPHA = 0.38f

        fun show(activity: FragmentActivity) {
            if (activity.supportFragmentManager.findFragmentByTag(TAG) == null) {
                VideoSoundSheet().show(activity.supportFragmentManager, TAG)
            }
        }

        private fun presetLabel(preset: EqualizerPreset): Int = when (preset) {
            EqualizerPreset.FLAT -> R.string.audio_preset_flat
            EqualizerPreset.BASS -> R.string.audio_preset_bass
            EqualizerPreset.TREBLE -> R.string.audio_preset_treble
            EqualizerPreset.VOCAL -> R.string.audio_preset_vocal
            EqualizerPreset.ROCK -> R.string.audio_preset_rock
            EqualizerPreset.POP -> R.string.audio_preset_pop
            EqualizerPreset.JAZZ -> R.string.audio_preset_jazz
            EqualizerPreset.CLASSICAL -> R.string.audio_preset_classical
            EqualizerPreset.LOUDNESS_AT_NIGHT -> R.string.audio_preset_night
            EqualizerPreset.CUSTOM -> R.string.audio_preset_custom
        }
    }
}
