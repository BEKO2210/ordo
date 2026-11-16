/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.AudioSoundSettingsSheetBinding
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
    private val bandValues = mutableListOf<TextView>()
    private lateinit var preGainValue: TextView
    private lateinit var bassValue: TextView
    private lateinit var virtualizerValue: TextView
    private lateinit var normalizationPreampValue: TextView
    private lateinit var speedValue: TextView
    private lateinit var balanceValue: TextView
    private var initializing = true

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
        settings = AudioSettingsStore.read()
        binding.close.setOnClickListener { dismiss() }
        createPresets()
        createBandSteppers()
        preGainValue = createStepper(
            binding.preGainRow, R.string.audio_sound_pre_gain,
            { settings.preGainDb }, { update { copy(preGainDb = it) } }, 0.5f,
            AudioSettings.MIN_GAIN_DB, AudioSettings.MAX_GAIN_DB, ::formatDb
        )
        bassValue = createIntStepper(
            binding.bassStrengthRow, R.string.audio_sound_strength,
            { settings.bassBoostStrength }, { update { copy(bassBoostStrength = it) } }, 50, 0, 1_000
        )
        virtualizerValue = createIntStepper(
            binding.virtualizerStrengthRow, R.string.audio_sound_strength,
            { settings.virtualizerStrength }, { update { copy(virtualizerStrength = it) } }, 50, 0, 1_000
        )
        normalizationPreampValue = createStepper(
            binding.normalizationPreampRow, R.string.audio_sound_normalization_preamp,
            { settings.normalizationPreampDb }, { update { copy(normalizationPreampDb = it) } },
            0.5f, AudioSettings.MIN_GAIN_DB, AudioSettings.MAX_GAIN_DB, ::formatDb
        )
        speedValue = createStepper(
            binding.speedRow, R.string.audio_sound_speed,
            { settings.speed }, { update { copy(speed = it) } }, 0.05f, 0.5f, 2f
        ) { getString(R.string.audio_sound_speed_value, it) }
        balanceValue = createIntStepper(
            binding.balanceRow, R.string.audio_sound_balance,
            { settings.balance }, { update { copy(balance = it) } }, 5, -100, 100
        ) { formatBalance(it) }

        binding.equalizerCurve.onGainChanged = { index, gain ->
            val bands = settings.bandGainsDb.toMutableList().apply { this[index] = gain }
            update { copy(preset = EqualizerPreset.CUSTOM, bandGainsDb = bands) }
        }
        binding.equalizerEnabled.setOnCheckedChangeListener { _, checked ->
            if (!initializing) update { copy(equalizerEnabled = checked) }
        }
        binding.bassEnabled.setOnCheckedChangeListener { _, checked ->
            if (!initializing) update { copy(bassBoostEnabled = checked) }
        }
        binding.virtualizerEnabled.setOnCheckedChangeListener { _, checked ->
            if (!initializing) update { copy(virtualizerEnabled = checked) }
        }
        binding.normalizationEnabled.setOnCheckedChangeListener { _, checked ->
            if (!initializing) update { copy(normalizationEnabled = checked) }
        }
        binding.skipSilence.setOnCheckedChangeListener { _, checked ->
            if (!initializing) update { copy(skipSilence = checked) }
        }
        binding.mono.setOnCheckedChangeListener { _, checked ->
            if (!initializing) update { copy(mono = checked) }
        }
        binding.resumeHeadphones.setOnCheckedChangeListener { _, checked ->
            if (!initializing) update { copy(resumeOnHeadphones = checked) }
        }
        binding.focusBehavior.setOnCheckedChangeListener { _, id ->
            if (!initializing) update {
                copy(focusBehavior = if (id == R.id.focusPause) AudioFocusBehavior.PAUSE else AudioFocusBehavior.DUCK)
            }
        }
        createSleepOptions()
        render()
        initializing = false
        refreshStatus()
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        if (!Motion.any) dialog.window?.setWindowAnimations(0)
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val maxWidth = (640 * resources.displayMetrics.density).roundToInt()
        sheet.layoutParams = sheet.layoutParams.apply {
            width = minOf(resources.displayMetrics.widthPixels, maxWidth)
        }
        (sheet.layoutParams as? android.widget.FrameLayout.LayoutParams)?.gravity = android.view.Gravity.CENTER_HORIZONTAL
        BottomSheetBehavior.from(sheet).state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun createPresets() {
        val labels = mapOf(
            EqualizerPreset.FLAT to R.string.audio_preset_flat,
            EqualizerPreset.BASS to R.string.audio_preset_bass,
            EqualizerPreset.TREBLE to R.string.audio_preset_treble,
            EqualizerPreset.VOCAL to R.string.audio_preset_vocal,
            EqualizerPreset.ROCK to R.string.audio_preset_rock,
            EqualizerPreset.POP to R.string.audio_preset_pop,
            EqualizerPreset.JAZZ to R.string.audio_preset_jazz,
            EqualizerPreset.CLASSICAL to R.string.audio_preset_classical,
            EqualizerPreset.LOUDNESS_AT_NIGHT to R.string.audio_preset_night,
            EqualizerPreset.CUSTOM to R.string.audio_preset_custom
        )
        labels.forEach { (preset, label) ->
            binding.presets.addView(Chip(requireContext()).apply {
                id = View.generateViewId()
                text = getString(label)
                isCheckable = true
                tag = preset
                minHeight = dp(48)
                setOnClickListener {
                    if (!initializing) update {
                        copy(preset = preset, bandGainsDb = if (preset == EqualizerPreset.CUSTOM) bandGainsDb else preset.gainsDb)
                    }
                }
            })
        }
    }

    private fun createBandSteppers() {
        AudioSettings.BAND_FREQUENCIES_HZ.forEachIndexed { index, frequency ->
            val label = if (frequency >= 1_000) getString(R.string.audio_frequency_khz, frequency / 1_000)
            else getString(R.string.audio_frequency_hz, frequency)
            bandValues += createStepper(
                binding.bandSteppers, label,
                { settings.bandGainsDb[index] },
                { gain ->
                    val bands = settings.bandGainsDb.toMutableList().apply { this[index] = gain }
                    update { copy(preset = EqualizerPreset.CUSTOM, bandGainsDb = bands) }
                },
                0.5f, AudioSettings.MIN_GAIN_DB, AudioSettings.MAX_GAIN_DB, ::formatDb
            )
        }
    }

    private fun createStepper(
        container: LinearLayout,
        labelRes: Int,
        getter: () -> Float,
        setter: (Float) -> Unit,
        step: Float,
        minimum: Float,
        maximum: Float,
        formatter: (Float) -> String = { it.toString() }
    ): TextView = createStepper(container, getString(labelRes), getter, setter, step, minimum, maximum, formatter)

    private fun createStepper(
        container: LinearLayout,
        label: String,
        getter: () -> Float,
        setter: (Float) -> Unit,
        step: Float,
        minimum: Float,
        maximum: Float,
        formatter: (Float) -> String
    ): TextView {
        val value = TextView(requireContext()).apply {
            gravity = android.view.Gravity.CENTER
            minWidth = dp(72)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        }
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            addView(TextView(context).apply {
                text = label
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(stepButton("−", getString(R.string.audio_sound_decrease, label)) {
                setter((getter() - step).coerceAtLeast(minimum))
            })
            addView(value, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(stepButton("+", getString(R.string.audio_sound_increase, label)) {
                setter((getter() + step).coerceAtMost(maximum))
            })
        }
        value.tag = formatter
        container.addView(row)
        return value
    }

    private fun createIntStepper(
        container: LinearLayout,
        labelRes: Int,
        getter: () -> Int,
        setter: (Int) -> Unit,
        step: Int,
        minimum: Int,
        maximum: Int,
        formatter: (Int) -> String = Int::toString
    ): TextView = createStepper(
        container, getString(labelRes), { getter().toFloat() }, { setter(it.roundToInt()) },
        step.toFloat(), minimum.toFloat(), maximum.toFloat()
    ) { formatter(it.roundToInt()) }

    private fun stepButton(symbol: String, description: String, action: () -> Unit) =
        MaterialButton(requireContext()).apply {
            text = symbol
            contentDescription = description
            minWidth = dp(48)
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { action() }
        }

    private fun createSleepOptions() {
        listOf(
            getString(R.string.audio_sleep_off) to (0 to false),
            getString(R.string.audio_sleep_minutes, 15) to (15 to false),
            getString(R.string.audio_sleep_minutes, 30) to (30 to false),
            getString(R.string.audio_sleep_minutes, 45) to (45 to false),
            getString(R.string.audio_sleep_minutes, 60) to (60 to false),
            getString(R.string.audio_sleep_end_track) to (0 to true)
        ).forEach { (label, timer) ->
            binding.sleepOptions.addView(Chip(requireContext()).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                tag = timer
                minHeight = dp(48)
                setOnClickListener {
                    (activity as? AudioPlayerActivity)?.setSleepTimer(
                        timer.first, timer.second, ::refreshStatus
                    )
                }
            })
        }
    }

    private fun update(transform: AudioSettings.() -> AudioSettings) {
        settings = settings.transform().sanitized()
        AudioSettingsStore.write(settings)
        (activity as? AudioPlayerActivity)?.applySoundSettings(::refreshStatus)
        render()
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderValue(view: TextView, value: Float) {
        val formatter = view.tag as? (Float) -> String ?: return
        view.text = formatter(value)
        ViewCompat.setStateDescription(view, view.text)
    }

    private fun render() {
        binding.equalizerEnabled.isChecked = settings.equalizerEnabled
        binding.equalizerCurve.gainsDb = settings.bandGainsDb
        binding.presets.children().filterIsInstance<Chip>().forEach { it.isChecked = it.tag == settings.preset }
        bandValues.forEachIndexed { index, view -> renderValue(view, settings.bandGainsDb[index]) }
        renderValue(preGainValue, settings.preGainDb)
        binding.bassEnabled.isChecked = settings.bassBoostEnabled
        renderValue(bassValue, settings.bassBoostStrength.toFloat())
        binding.virtualizerEnabled.isChecked = settings.virtualizerEnabled
        renderValue(virtualizerValue, settings.virtualizerStrength.toFloat())
        binding.normalizationEnabled.isChecked = settings.normalizationEnabled
        renderValue(normalizationPreampValue, settings.normalizationPreampDb)
        renderValue(speedValue, settings.speed)
        binding.skipSilence.isChecked = settings.skipSilence
        binding.mono.isChecked = settings.mono
        renderValue(balanceValue, settings.balance.toFloat())
        binding.focusBehavior.check(
            if (settings.focusBehavior == AudioFocusBehavior.PAUSE) R.id.focusPause else R.id.focusDuck
        )
        binding.resumeHeadphones.isChecked = settings.resumeOnHeadphones
    }

    private fun showStatus(status: AudioPlayerActivity.SoundStatus) {
        binding.effectStatus.text = when {
            !status.equalizerAvailable -> getString(R.string.audio_sound_effect_unavailable)
            status.usingDynamicsProcessing -> getString(R.string.audio_sound_effect_dp)
            else -> getString(R.string.audio_sound_effect_legacy, status.legacyBandCount)
        }
        val eqEnabled = status.equalizerAvailable
        setEnabledRecursively(binding.equalizerEnabled, eqEnabled)
        setEnabledRecursively(binding.presets, eqEnabled)
        setEnabledRecursively(binding.equalizerCurve, eqEnabled)
        setEnabledRecursively(binding.bandSteppers, eqEnabled)
        setEnabledRecursively(binding.preGainRow, status.inputGainAvailable)
        setEnabledRecursively(binding.normalizationEnabled, status.inputGainAvailable)
        setEnabledRecursively(binding.normalizationPreampRow, status.inputGainAvailable)
        // Per-channel input gain is available in DynamicsProcessing, not the API 24-27 Equalizer.
        setEnabledRecursively(binding.balanceRow, status.usingDynamicsProcessing)
        binding.bassSection.isVisible = status.bassBoostAvailable
        binding.virtualizerSection.isVisible = status.virtualizerAvailable
        val activeTimer = if (status.sleepActive) {
            status.sleepMinutes to status.sleepAtEndOfTrack
        } else {
            0 to false
        }
        binding.sleepOptions.children().filterIsInstance<Chip>().forEach {
            it.isChecked = it.tag == activeTimer
        }
    }

    internal fun refreshStatus() {
        if (_binding != null) (activity as? AudioPlayerActivity)?.querySoundStatus(::showStatus)
    }

    private fun setEnabledRecursively(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) view.children().forEach { setEnabledRecursively(it, enabled) }
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
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "audioSoundSettings"
    }
}
