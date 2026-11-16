/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import java.text.NumberFormat
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.AudioSoundSettingsSheetBinding
import me.zhanghai.android.files.databinding.KlangEffectCardBinding
import me.zhanghai.android.files.databinding.KlangPresetTileBinding
import me.zhanghai.android.files.databinding.KlangStepButtonsBinding
import me.zhanghai.android.files.databinding.KlangStepperRowBinding
import me.zhanghai.android.files.databinding.KlangSwitchRowBinding
import me.zhanghai.android.files.player.AudioFocusBehavior
import me.zhanghai.android.files.player.AudioSettings
import me.zhanghai.android.files.player.AudioSettingsStore
import me.zhanghai.android.files.player.EqualizerPreset
import me.zhanghai.android.files.ui.Motion
import kotlin.math.roundToInt

class AudioSoundSettingsSheet : BottomSheetDialogFragment() {
    private var _binding: AudioSoundSettingsSheetBinding? = null
    private val binding get() = checkNotNull(_binding)
    private var settings = AudioSettings()
    private val renderers = mutableListOf<() -> Unit>()
    private val tiles = mutableListOf<PresetTile>()
    private val sleepButtons = mutableListOf<Pair<MaterialButton, Pair<Int, Boolean>>>()
    private val handler = Handler(Looper.getMainLooper())
    private val sleepTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, SLEEP_REFRESH_MS)
        }
    }
    private var initializing = true
    private var rendering = false
    private var fineTuneExpanded = false
    private var firstRender = true
    private var soundStatus: AudioPlayerActivity.SoundStatus? = null
    private lateinit var palette: Palette

    private class Palette(
        val primary: Int,
        val onSurface: Int,
        val card: Int,
        val stage: Int,
        val selected: Int,
        val onSelected: Int
    )

    private class PresetTile(
        val preset: EqualizerPreset,
        val binding: KlangPresetTileBinding,
        val curve: PresetCurveDrawable
    ) {
        var selected = false
        var spring: SpringAnimation? = null
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        BottomSheetDialog(requireContext(), theme)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = AudioSoundSettingsSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        initializing = true
        firstRender = true
        soundStatus = null
        renderers.clear()
        tiles.clear()
        sleepButtons.clear()
        settings = AudioSettingsStore.read()
        palette = resolvePalette()
        applyPalette()
        binding.close.setOnClickListener { dismiss() }
        binding.fineTuneToggle.setOnClickListener { setFineTuneExpanded(!fineTuneExpanded, true) }
        setFineTuneExpanded(false, false)
        createPresets()
        createBandSteppers()
        bindStepper(
            binding.preGain, getString(R.string.audio_sound_pre_gain),
            { settings.preGainDb }, { update { copy(preGainDb = it) } }, 0.5f,
            AudioSettings.MIN_GAIN_DB, AudioSettings.MAX_GAIN_DB, ::formatDb
        )
        bindEffectCard(
            binding.bassCard, R.drawable.audio_speaker_icon_24dp, R.string.audio_sound_bass_boost,
            R.string.audio_sound_bass_summary, { settings.bassBoostEnabled },
            { enabled -> update { copy(bassBoostEnabled = enabled) } },
            { settings.bassBoostStrength }, { update { copy(bassBoostStrength = it) } }
        )
        bindEffectCard(
            binding.virtualizerCard, R.drawable.audio_surround_icon_24dp, R.string.audio_sound_virtualizer,
            R.string.audio_sound_virtualizer_summary, { settings.virtualizerEnabled },
            { enabled -> update { copy(virtualizerEnabled = enabled) } },
            { settings.virtualizerStrength }, { update { copy(virtualizerStrength = it) } }
        )
        bindSwitchRow(
            binding.normalizationRow, R.drawable.audio_graphic_eq_icon_24dp, R.string.audio_sound_normalization,
            R.string.audio_sound_normalization_summary, { settings.normalizationEnabled }
        ) { update { copy(normalizationEnabled = it) } }
        bindStepper(
            binding.normalizationPreamp, getString(R.string.audio_sound_normalization_preamp),
            { settings.normalizationPreampDb }, { update { copy(normalizationPreampDb = it) } },
            0.5f, AudioSettings.MIN_GAIN_DB, AudioSettings.MAX_GAIN_DB, ::formatDb
        )
        bindSwitchRow(
            binding.monoRow, R.drawable.audio_hearing_icon_24dp, R.string.audio_sound_mono,
            R.string.audio_sound_mono_summary, { settings.mono }
        ) { update { copy(mono = it) } }
        bindSwitchRow(
            binding.skipRow, R.drawable.audio_fast_forward_icon_24dp, R.string.audio_sound_skip_silence,
            R.string.audio_sound_skip_silence_summary, { settings.skipSilence }
        ) { update { copy(skipSilence = it) } }
        bindSwitchRow(
            binding.headphonesRow, R.drawable.audio_headphones_icon_24dp, R.string.audio_sound_resume_headphones,
            R.string.audio_sound_headphones_summary, { settings.resumeOnHeadphones }
        ) { update { copy(resumeOnHeadphones = it) } }
        bindBalance()
        bindSpeed()
        bindFocus()
        createSleepOptions()

        binding.equalizerCurve.ringColor = palette.stage
        binding.equalizerCurve.onGainChanged = { index, gain ->
            val bands = settings.bandGainsDb.toMutableList().apply { this[index] = gain }
            update { copy(preset = EqualizerPreset.CUSTOM, bandGainsDb = bands) }
        }
        binding.equalizerReset.setOnClickListener {
            update { copy(preset = EqualizerPreset.FLAT, bandGainsDb = EqualizerPreset.FLAT.gainsDb) }
        }
        binding.equalizerEnabled.setOnCheckedChangeListener { _, checked ->
            if (!initializing && !rendering) update { copy(equalizerEnabled = checked) }
        }
        render()
        initializing = false
        refreshStatus()
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        if (!Motion.any) dialog.window?.setWindowAnimations(0)
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val maxWidth = resources.getDimensionPixelSize(R.dimen.klang_sheet_max_width)
        sheet.layoutParams = sheet.layoutParams.apply {
            width = minOf(resources.displayMetrics.widthPixels, maxWidth)
        }
        (sheet.layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.CENTER_HORIZONTAL
        BottomSheetBehavior.from(sheet).state = BottomSheetBehavior.STATE_EXPANDED
        handler.removeCallbacks(sleepTicker)
        handler.postDelayed(sleepTicker, SLEEP_REFRESH_MS)
    }

    override fun onStop() {
        handler.removeCallbacks(sleepTicker)
        super.onStop()
    }

    // The surface-container roles exist only in the Material 3 theme family; the other family gets tinted layers.
    private fun resolvePalette(): Palette {
        val view = binding.root
        val surface = MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF.toInt())
        val onSurface = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface, 0xFF1C1B1F.toInt())
        val primary = MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4.toInt())
        return Palette(
            primary = primary,
            onSurface = onSurface,
            card = MaterialColors.getColor(
                view, com.google.android.material.R.attr.colorSurfaceContainerHigh,
                MaterialColors.layer(surface, onSurface, 0.08f)
            ),
            stage = MaterialColors.getColor(
                view, com.google.android.material.R.attr.colorSurfaceContainerHighest,
                MaterialColors.layer(surface, onSurface, 0.11f)
            ),
            selected = MaterialColors.getColor(
                view, com.google.android.material.R.attr.colorPrimaryContainer,
                MaterialColors.layer(surface, primary, 0.24f)
            ),
            onSelected = MaterialColors.getColor(
                view, com.google.android.material.R.attr.colorOnPrimaryContainer, onSurface
            )
        )
    }

    private fun applyPalette() {
        binding.equalizerStage.setCardBackgroundColor(palette.stage)
        listOf(
            binding.fineTuneCard, binding.bassCard.root, binding.virtualizerCard.root,
            binding.normalizationCard, binding.balanceCard, binding.monoCard,
            binding.speedCard, binding.sleepCard, binding.optionsCard
        ).forEach { it.setCardBackgroundColor(palette.card) }
        binding.balanceView.setBackgroundSurface(palette.card)
    }

    private fun pill(view: TextView) {
        view.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(MaterialColors.layer(palette.card, palette.primary, 0.16f))
        }
    }

    private fun createPresets() {
        EqualizerPreset.entries.forEach { preset ->
            val tileBinding = KlangPresetTileBinding.inflate(layoutInflater, binding.presets, false)
            val curve = PresetCurveDrawable(resources.displayMetrics.density)
            curve.setGains(preset.gainsDb)
            tileBinding.curve.setImageDrawable(curve)
            tileBinding.name.text = getString(presetLabel(preset))
            tileBinding.root.layoutParams = LinearLayout.LayoutParams(dp(96), dp(92)).apply { marginEnd = dp(8) }
            tileBinding.root.isVisible = preset != EqualizerPreset.CUSTOM
            val tile = PresetTile(preset, tileBinding, curve)
            tileBinding.root.setOnClickListener {
                if (!initializing && preset != EqualizerPreset.CUSTOM) update {
                    copy(preset = preset, bandGainsDb = preset.gainsDb)
                }
            }
            ViewCompat.setAccessibilityDelegate(tileBinding.root, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.RadioButton"
                    info.isCheckable = true
                    info.isChecked = tile.selected
                }
            })
            tiles += tile
            binding.presets.addView(tileBinding.root)
            applyTile(tile, false)
        }
    }

    // The selected tile gets container colours and rounder corners; the corner change is a spring.
    private fun applyTile(tile: PresetTile, animate: Boolean) {
        val selected = tile.selected
        val card = tile.binding.root
        card.setCardBackgroundColor(if (selected) palette.selected else palette.card)
        tile.binding.name.setTextColor(if (selected) palette.onSelected else palette.onSurface)
        tile.curve.setColor(if (selected) palette.onSelected else palette.primary)
        tile.binding.check.imageTintList = ColorStateList.valueOf(palette.onSelected)
        tile.binding.check.isVisible = selected
        val target = dp(if (selected) 28 else 16).toFloat()
        tile.spring?.cancel()
        if (animate && Motion.any && card.isLaidOut) {
            val property = object : FloatPropertyCompat<MaterialCardView>("tileRadius") {
                override fun getValue(view: MaterialCardView) = view.radius
                override fun setValue(view: MaterialCardView, value: Float) {
                    view.radius = value
                }
            }
            tile.spring = SpringAnimation(card, property, target).apply {
                spring.setStiffness(SpringForce.STIFFNESS_MEDIUM)
                    .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                start()
            }
        } else {
            card.radius = target
        }
        tile.binding.curve.invalidate()
    }

    private fun createBandSteppers() {
        AudioSettings.BAND_FREQUENCIES_HZ.forEachIndexed { index, frequency ->
            val label = if (frequency >= 1_000) getString(R.string.audio_frequency_khz, frequency / 1_000)
            else getString(R.string.audio_frequency_hz, frequency)
            val row = KlangStepperRowBinding.inflate(layoutInflater, binding.bandSteppers, true)
            bindStepper(
                row, label,
                { settings.bandGainsDb[index] },
                { gain ->
                    val bands = settings.bandGainsDb.toMutableList().apply { this[index] = gain }
                    update { copy(preset = EqualizerPreset.CUSTOM, bandGainsDb = bands) }
                },
                0.5f, AudioSettings.MIN_GAIN_DB, AudioSettings.MAX_GAIN_DB, ::formatDb
            )
        }
    }

    private fun bindStepper(
        row: KlangStepperRowBinding,
        label: String,
        getter: () -> Float,
        setter: (Float) -> Unit,
        step: Float,
        minimum: Float,
        maximum: Float,
        formatter: (Float) -> String
    ) {
        row.stepLabel.text = label
        pill(row.stepValue)
        bindStepButtons(
            row.steps, getString(R.string.audio_sound_decrease, label), getString(R.string.audio_sound_increase, label),
            { setter((getter() - step).coerceAtLeast(minimum)) },
            { setter((getter() + step).coerceAtMost(maximum)) }
        )
        renderers += {
            row.stepValue.text = formatter(getter())
            ViewCompat.setStateDescription(row.stepValue, row.stepValue.text)
        }
    }

    private fun bindStepButtons(
        buttons: KlangStepButtonsBinding,
        decrease: String,
        increase: String,
        onMinus: () -> Unit,
        onPlus: () -> Unit
    ) {
        // The group makes its members checkable; these are plain buttons.
        buttons.stepMinus.isCheckable = false
        buttons.stepPlus.isCheckable = false
        buttons.stepMinus.contentDescription = decrease
        buttons.stepPlus.contentDescription = increase
        buttons.stepMinus.setOnClickListener { onMinus() }
        buttons.stepPlus.setOnClickListener { onPlus() }
    }

    private fun bindSwitchRow(
        row: KlangSwitchRowBinding,
        icon: Int,
        title: Int,
        summary: Int,
        getter: () -> Boolean,
        setter: (Boolean) -> Unit
    ) {
        row.icon.setImageResource(icon)
        row.title.text = getString(title)
        row.summary.text = getString(summary)
        row.toggle.contentDescription = getString(title)
        row.toggle.setOnCheckedChangeListener { _, checked ->
            if (!initializing && !rendering) setter(checked)
        }
        row.root.setOnClickListener { if (row.toggle.isEnabled) row.toggle.toggle() }
        renderers += { row.toggle.isChecked = getter() }
    }

    private fun bindEffectCard(
        card: KlangEffectCardBinding,
        icon: Int,
        title: Int,
        summary: Int,
        enabled: () -> Boolean,
        setEnabled: (Boolean) -> Unit,
        strength: () -> Int,
        setStrength: (Int) -> Unit
    ) {
        card.icon.setImageResource(icon)
        card.title.text = getString(title)
        card.summary.text = getString(summary)
        card.toggle.contentDescription = getString(title)
        card.toggle.setOnCheckedChangeListener { _, checked ->
            if (!initializing && !rendering) setEnabled(checked)
        }
        val label = getString(R.string.audio_sound_strength)
        bindStepButtons(
            card.steps, getString(R.string.audio_sound_decrease, label), getString(R.string.audio_sound_increase, label),
            { setStrength((strength() - STRENGTH_STEP).coerceAtLeast(0)) },
            { setStrength((strength() + STRENGTH_STEP).coerceAtMost(1_000)) }
        )
        renderers += {
            card.toggle.isChecked = enabled()
            val percent = KlangMath.strengthPercent(strength())
            card.ring.setProgressCompat(percent, Motion.any && !firstRender)
            card.ring.alpha = if (enabled()) 1f else DISABLED_ALPHA
            card.state.text = getString(R.string.audio_sound_strength_percent, percent)
        }
    }

    private fun bindBalance() {
        pill(binding.balanceValue)
        val label = getString(R.string.audio_sound_balance)
        bindStepButtons(
            binding.balanceSteps, getString(R.string.audio_sound_decrease, label), getString(R.string.audio_sound_increase, label),
            { update { copy(balance = (balance - 5).coerceAtLeast(-100)) } },
            { update { copy(balance = (balance + 5).coerceAtMost(100)) } }
        )
        binding.balanceCenter.setOnClickListener { update { copy(balance = 0) } }
        renderers += {
            binding.balanceView.balance = settings.balance
            binding.balanceValue.text = formatBalance(settings.balance)
            ViewCompat.setStateDescription(binding.balanceView, binding.balanceValue.text)
        }
    }

    private fun bindSpeed() {
        val format = NumberFormat.getInstance().apply { maximumFractionDigits = 2 }
        val group = binding.speedGroup
        val buttons = listOf(
            binding.speedOption0, binding.speedOption1, binding.speedOption2,
            binding.speedOption3, binding.speedOption4
        )
        buttons.forEachIndexed { index, button ->
            button.text = "${format.format(KlangMath.SPEED_OPTIONS[index])}×"
        }
        group.addOnButtonCheckedListener { _, checkedId, checked ->
            if (initializing || rendering) return@addOnButtonCheckedListener
            val index = buttons.indexOfFirst { it.id == checkedId }
            if (index < 0) return@addOnButtonCheckedListener
            if (checked) {
                update { copy(speed = KlangMath.SPEED_OPTIONS[index]) }
            } else {
                // Tapping the selected option again clears it, but the speed is still that option.
                group.post { if (_binding != null) render() }
            }
        }
        val label = getString(R.string.audio_sound_speed)
        bindStepButtons(
            binding.speedSteps, getString(R.string.audio_sound_decrease, label),
            getString(R.string.audio_sound_increase, label),
            { update { copy(speed = roundSpeed((speed - 0.05f).coerceAtLeast(0.5f))) } },
            { update { copy(speed = roundSpeed((speed + 0.05f).coerceAtMost(2f))) } }
        )
        renderers += {
            binding.speedValue.text = getString(R.string.audio_sound_speed_value, settings.speed)
            ViewCompat.setStateDescription(binding.speedValue, binding.speedValue.text)
            val selected = KlangMath.speedOptionIndex(settings.speed)
            if (selected < 0) group.clearChecked() else group.check(buttons[selected].id)
        }
    }

    private fun roundSpeed(value: Float): Float = (value * 100).roundToInt() / 100f

    private fun bindFocus() {
        binding.focusBehavior.addOnButtonCheckedListener { _, checkedId, checked ->
            if (initializing || rendering || !checked) return@addOnButtonCheckedListener
            update {
                copy(focusBehavior = if (checkedId == R.id.focusPause) AudioFocusBehavior.PAUSE else AudioFocusBehavior.DUCK)
            }
        }
        renderers += {
            binding.focusBehavior.check(
                if (settings.focusBehavior == AudioFocusBehavior.PAUSE) R.id.focusPause else R.id.focusDuck
            )
        }
    }

    private fun setFineTuneExpanded(expanded: Boolean, animate: Boolean) {
        fineTuneExpanded = expanded
        val animated = animate && Motion.any
        if (animated) {
            TransitionManager.beginDelayedTransition(binding.content, AutoTransition().setDuration(200))
        }
        binding.fineTuneContent.isVisible = expanded
        binding.fineTuneChevron.animate().cancel()
        if (animated) {
            binding.fineTuneChevron.animate().rotation(if (expanded) 180f else 0f).setDuration(200).start()
        } else {
            binding.fineTuneChevron.rotation = if (expanded) 180f else 0f
        }
        ViewCompat.setStateDescription(
            binding.fineTuneToggle,
            getString(if (expanded) R.string.audio_sound_expanded else R.string.audio_sound_collapsed)
        )
        if (animate) binding.fineTuneToggle.announceForAccessibility(
            getString(if (expanded) R.string.audio_sound_fine_tune_expanded else R.string.audio_sound_fine_tune_collapsed)
        )
    }

    private fun createSleepOptions() {
        val options = listOf(
            getString(R.string.audio_sleep_off) to (0 to false),
            getString(R.string.audio_sleep_minutes, 15) to (15 to false),
            getString(R.string.audio_sleep_minutes, 30) to (30 to false),
            getString(R.string.audio_sleep_minutes, 45) to (45 to false),
            getString(R.string.audio_sleep_minutes, 60) to (60 to false),
            getString(R.string.audio_sleep_end_track) to (0 to true)
        )
        val buttons = listOf(
            binding.sleepOption0, binding.sleepOption1, binding.sleepOption2,
            binding.sleepOption3, binding.sleepOption4, binding.sleepOption5
        )
        options.forEachIndexed { index, (label, timer) ->
            val button = buttons[index]
            button.text = label
            button.setOnClickListener {
                (activity as? AudioPlayerActivity)?.setSleepTimer(timer.first, timer.second, ::refreshStatus)
            }
            sleepButtons += button to timer
        }
        binding.sleepRemaining.setOnClickListener { cancelSleep() }
        binding.sleepRemaining.setOnCloseIconClickListener { cancelSleep() }
        binding.sleepRemaining.closeIconContentDescription = getString(R.string.audio_sleep_cancel)
    }

    private fun cancelSleep() {
        (activity as? AudioPlayerActivity)?.setSleepTimer(0, false, ::refreshStatus)
    }

    private fun update(transform: AudioSettings.() -> AudioSettings) {
        settings = settings.transform().sanitized()
        AudioSettingsStore.write(settings)
        (activity as? AudioPlayerActivity)?.applySoundSettings(::refreshStatus)
        render()
    }

    private fun render() {
        val wasRendering = rendering
        rendering = true
        binding.equalizerEnabled.isChecked = settings.equalizerEnabled
        binding.equalizerCurve.gainsDb = settings.bandGainsDb
        binding.equalizerReset.isInvisible = KlangMath.isFlat(settings.bandGainsDb)
        var selectedTile: PresetTile? = null
        tiles.forEach { tile ->
            val selected = tile.preset == settings.preset
            if (tile.preset == EqualizerPreset.CUSTOM) {
                tile.binding.root.isVisible = selected
                if (selected) tile.curve.setGains(settings.bandGainsDb)
            }
            if (selected) selectedTile = tile
            if (selected != tile.selected || firstRender) {
                tile.selected = selected
                applyTile(tile, !firstRender)
            }
        }
        val initialTile = selectedTile
        if (firstRender && initialTile != null) {
            initialTile.binding.root.post {
                val scroller = binding.presets.parent as? HorizontalScrollView
                scroller?.scrollTo(maxOf(0, initialTile.binding.root.left - dp(20)), 0)
            }
        }
        renderers.forEach { it() }
        renderEqualizerState()
        firstRender = false
        rendering = wasRendering
    }

    private fun showStatus(status: AudioPlayerActivity.SoundStatus) {
        soundStatus = status
        renderEqualizerState()
        enableCard(binding.normalizationRow.root, status.inputGainAvailable)
        enableCard(binding.normalizationPreamp.root, status.inputGainAvailable)
        // Per-channel input gain is available in DynamicsProcessing, not the API 24-27 Equalizer.
        enableCard(binding.balanceCard, status.usingDynamicsProcessing)
        binding.effectsSection.isVisible = true
        showEffect(binding.bassCard.root, binding.bassUnavailable, status.bassBoostAvailable, R.string.audio_sound_bass_boost)
        showEffect(
            binding.virtualizerCard.root, binding.virtualizerUnavailable, status.virtualizerAvailable,
            R.string.audio_sound_virtualizer
        )
        val activeTimer = if (status.sleepActive) status.sleepMinutes to status.sleepAtEndOfTrack else 0 to false
        sleepButtons.forEach { (button, timer) -> button.isChecked = timer == activeTimer }
        val chip = binding.sleepRemaining
        chip.isVisible = status.sleepActive
        if (status.sleepActive) {
            chip.text = if (status.sleepAtEndOfTrack) getString(R.string.audio_sleep_until_end)
            else getString(R.string.audio_sleep_remaining, KlangMath.remainingMinutes(status.sleepRemainingMs))
        }
    }

    private fun showEffect(card: View, unavailable: TextView, available: Boolean, name: Int) {
        card.isVisible = available
        unavailable.isVisible = !available
        unavailable.text = getString(R.string.audio_sound_unavailable_row, getString(name))
    }

    private fun enableCard(view: View, enabled: Boolean) {
        setEnabledRecursively(view, enabled)
        view.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    internal fun refreshStatus() {
        if (_binding != null) (activity as? AudioPlayerActivity)?.querySoundStatus(::showStatus)
    }

    private fun setEnabledRecursively(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) view.children().forEach { setEnabledRecursively(it, enabled) }
    }

    private fun renderEqualizerState() {
        val status = soundStatus
        val kind = status?.let { equalizerStatusKind(it.equalizerAvailable, settings.equalizerEnabled) }
        if (kind != null) {
            binding.effectStatus.text = when (kind) {
                EqualizerStatusKind.UNSUPPORTED -> getString(R.string.audio_sound_effect_unavailable)
                EqualizerStatusKind.OFF -> getString(R.string.audio_sound_off)
                EqualizerStatusKind.PRESET ->
                    if (settings.preset == EqualizerPreset.CUSTOM) getString(R.string.audio_sound_status_custom)
                    else getString(presetLabel(settings.preset))
            }
        }
        binding.equalizerCurve.offText = when (kind) {
            EqualizerStatusKind.UNSUPPORTED -> getString(R.string.audio_sound_effect_unavailable)
            EqualizerStatusKind.OFF -> getString(R.string.audio_sound_curve_off)
            else -> null
        }
        setEnabledRecursively(binding.equalizerEnabled, status?.equalizerAvailable == true)
        val controlsEnabled = status?.let {
            equalizerControlsEnabled(it.equalizerAvailable, settings.equalizerEnabled)
        } == true
        setEnabledRecursively(binding.equalizerControls, controlsEnabled)
        setEnabledRecursively(binding.preGain.root, controlsEnabled && status?.inputGainAvailable == true)
        binding.equalizerCurve.isEnabled = controlsEnabled
        binding.equalizerReset.isEnabled = controlsEnabled
        binding.equalizerControls.alpha = if (controlsEnabled) 1f else DISABLED_ALPHA
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

    private fun formatDb(value: Float): String = getString(R.string.audio_sound_db, value)

    private fun formatBalance(value: Int): String = when {
        value < 0 -> getString(R.string.audio_sound_balance_left, -value)
        value > 0 -> getString(R.string.audio_sound_balance_right, value)
        else -> getString(R.string.audio_sound_balance_center)
    }

    private fun ViewGroup.children(): Sequence<View> = sequence {
        for (index in 0 until childCount) yield(getChildAt(index))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    override fun onDestroyView() {
        handler.removeCallbacks(sleepTicker)
        tiles.forEach { it.spring?.cancel() }
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "audioSoundSettings"
        private const val DISABLED_ALPHA = 0.38f
        private const val STRENGTH_STEP = 50
        private const val SLEEP_REFRESH_MS = 30_000L
    }
}

internal enum class EqualizerStatusKind { UNSUPPORTED, OFF, PRESET }

internal fun equalizerStatusKind(available: Boolean, enabled: Boolean): EqualizerStatusKind = when {
    !available -> EqualizerStatusKind.UNSUPPORTED
    !enabled -> EqualizerStatusKind.OFF
    else -> EqualizerStatusKind.PRESET
}

internal fun equalizerControlsEnabled(available: Boolean, enabled: Boolean): Boolean = available && enabled
