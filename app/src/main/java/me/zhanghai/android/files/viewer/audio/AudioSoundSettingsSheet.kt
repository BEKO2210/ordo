/* Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later */
package me.zhanghai.android.files.viewer.audio

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.shape.ShapeAppearanceModel
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
    private var initializing = true
    private var fineTuneExpanded = false
    private var soundStatus: AudioPlayerActivity.SoundStatus? = null
    private val bandValues = mutableListOf<TextView>()
    private val presetCards = linkedMapOf<EqualizerPreset, MaterialCardView>()
    private val sleepButtons = linkedMapOf<Pair<Int, Boolean>, MaterialButton>()
    private lateinit var preGainValue: TextView
    private lateinit var bassValue: TextView
    private lateinit var virtualizerValue: TextView
    private lateinit var normalizationValue: TextView
    private lateinit var speedValue: TextView
    private lateinit var balanceValue: TextView
    private lateinit var bassSwitch: MaterialSwitch
    private lateinit var virtualizerSwitch: MaterialSwitch
    private lateinit var normalizationSwitch: MaterialSwitch
    private lateinit var bassRing: CircularProgressIndicator
    private lateinit var virtualizerRing: CircularProgressIndicator
    private lateinit var balanceDot: TextView
    private lateinit var speedOptions: MaterialButtonToggleGroup
    private lateinit var sleepActiveChip: Chip

    override fun onCreateDialog(state: Bundle?): Dialog = BottomSheetDialog(requireContext(), theme)
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = AudioSoundSettingsSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        settings = AudioSettingsStore.read()
        binding.equalizerStage.setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh, com.google.android.material.R.attr.colorSurface))
        binding.close.setOnClickListener { dismiss() }
        binding.fineTuneToggle.setOnClickListener { setFineTuneExpanded(!fineTuneExpanded, true) }
        binding.resetEqualizer.setOnClickListener { update { copy(preset = EqualizerPreset.FLAT, bandGainsDb = EqualizerPreset.FLAT.gainsDb) } }
        createPresets(); createBandSteppers()
        preGainValue = createStepper(binding.preGainRow, getString(R.string.audio_sound_pre_gain), { settings.preGainDb }, { value -> update { copy(preGainDb = value) } }, .5f, -12f, 12f, ::formatDb)
        createEffects(); createVolume(); createPlayback(); bindSwitches()
        binding.equalizerCurve.onGainChanged = { index, gain ->
            val bands = settings.bandGainsDb.toMutableList().apply { this[index] = gain }
            update { copy(preset = EqualizerPreset.CUSTOM, bandGainsDb = bands) }
        }
        binding.equalizerCurve.onDragStateChanged = { dragging -> binding.resetEqualizer.isVisible = !dragging && settings.bandGainsDb.any { it != 0f } }
        setFineTuneExpanded(false, false); render(); initializing = false; refreshStatus()
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        if (!Motion.any) dialog.window?.setWindowAnimations(0)
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        sheet.setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainerLow, com.google.android.material.R.attr.colorSurface))
        sheet.layoutParams = sheet.layoutParams.apply { width = minOf(resources.displayMetrics.widthPixels, resources.getDimensionPixelSize(R.dimen.audio_sound_sheet_max_width)) }
        (sheet.layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.CENTER_HORIZONTAL
        BottomSheetBehavior.from(sheet).state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun bindSwitches() {
        binding.equalizerEnabled.setOnCheckedChangeListener { _, value -> if (!initializing) update { copy(equalizerEnabled = value) } }
        bassSwitch.setOnCheckedChangeListener { _, value -> if (!initializing) update { copy(bassBoostEnabled = value) } }
        virtualizerSwitch.setOnCheckedChangeListener { _, value -> if (!initializing) update { copy(virtualizerEnabled = value) } }
        normalizationSwitch.setOnCheckedChangeListener { _, value -> if (!initializing) update { copy(normalizationEnabled = value) } }
        binding.skipSilence.setOnCheckedChangeListener { _, value -> if (!initializing) update { copy(skipSilence = value) } }
        binding.mono.setOnCheckedChangeListener { _, value -> if (!initializing) update { copy(mono = value) } }
        binding.resumeHeadphones.setOnCheckedChangeListener { _, value -> if (!initializing) update { copy(resumeOnHeadphones = value) } }
        binding.focusBehavior.addOnButtonCheckedListener { _, id, checked -> if (checked && !initializing) update { copy(focusBehavior = if (id == R.id.focusPause) AudioFocusBehavior.PAUSE else AudioFocusBehavior.DUCK) } }
    }

    private fun createPresets() = equalizerPresetRail(settings.preset).forEach(::addPreset)
    private fun addPreset(preset: EqualizerPreset) {
        if (presetCards.containsKey(preset)) return
        val card = MaterialCardView(requireContext()).apply {
            id = if (preset == EqualizerPreset.BASS) R.id.audioPresetBass else View.generateViewId()
            isClickable = true; isFocusable = true; isCheckable = true; tag = preset; radius = dp(16).toFloat(); strokeWidth = 0
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(92)).apply { marginEnd = dp(8) }
            addView(FrameLayout(context).apply {
                addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER; orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
                addView(ImageView(context).apply { setImageDrawable(PresetCurveDrawable(context, preset.gainsDb)); contentDescription = null }, LinearLayout.LayoutParams(dp(56), dp(30)))
                addView(TextView(context).apply { text = getString(presetLabel(preset)); gravity = Gravity.CENTER; maxLines = 2; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(TextView(context).apply { tag = PRESET_CHECK_TAG; text = "✓"; gravity = Gravity.CENTER; visibility = View.GONE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END))
            })
            setOnClickListener { if (!initializing) update { copy(preset = preset, bandGainsDb = if (preset == EqualizerPreset.CUSTOM) bandGainsDb else preset.gainsDb) } }
            ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) { super.onInitializeAccessibilityNodeInfo(host, info); info.className = android.widget.RadioButton::class.java.name }
            })
        }
        presetCards[preset] = card; binding.presets.addView(card)
    }

    private fun createBandSteppers() {
        AudioSettings.BAND_FREQUENCIES_HZ.forEachIndexed { index, hz ->
            bandValues += createStepper(binding.bandSteppers, bandLabel(hz), { settings.bandGainsDb[index] }, { gain ->
                val bands = settings.bandGainsDb.toMutableList().apply { this[index] = gain }; update { copy(preset = EqualizerPreset.CUSTOM, bandGainsDb = bands) }
            }, .5f, -12f, 12f, ::formatDb)
        }
    }

    private fun createEffects() {
        effectCard(binding.bassSection, R.drawable.audio_speaker_icon_24dp, R.string.audio_sound_bass_boost).also { bassSwitch = it.switch; bassRing = it.ring; bassValue = createStepper(it.controls, getString(R.string.audio_sound_strength), { settings.bassBoostStrength.toFloat() }, { value -> update { copy(bassBoostStrength = value.roundToInt()) } }, 50f, 0f, 1000f) { value -> getString(R.string.audio_sound_strength_percent, (value / 10).roundToInt()) } }
        effectCard(binding.virtualizerSection, R.drawable.audio_surround_icon_24dp, R.string.audio_sound_virtualizer).also { virtualizerSwitch = it.switch; virtualizerRing = it.ring; virtualizerValue = createStepper(it.controls, getString(R.string.audio_sound_strength), { settings.virtualizerStrength.toFloat() }, { value -> update { copy(virtualizerStrength = value.roundToInt()) } }, 50f, 0f, 1000f) { value -> getString(R.string.audio_sound_strength_percent, (value / 10).roundToInt()) } }
    }

    private fun createVolume() {
        simpleCard(binding.normalizationCard, R.drawable.audio_graphic_eq_icon_24dp, R.string.audio_sound_normalization).also { card ->
            normalizationSwitch = MaterialSwitch(requireContext()).apply { contentDescription = getString(R.string.audio_sound_normalization) }; card.header.addView(normalizationSwitch)
            normalizationValue = createStepper(card.body, getString(R.string.audio_sound_normalization_preamp), { settings.normalizationPreampDb }, { value -> update { copy(normalizationPreampDb = value) } }, .5f, -12f, 12f, ::formatDb)
        }
        simpleCard(binding.balanceCard, R.drawable.audio_balance_icon_24dp, R.string.audio_sound_balance).also { card ->
            balanceDot = TextView(requireContext()).apply { text = "●"; gravity = Gravity.CENTER; setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary, androidx.appcompat.R.attr.colorPrimary)) }
            card.body.addView(LinearLayout(requireContext()).apply { gravity = Gravity.CENTER; addView(symbol(R.drawable.audio_speaker_icon_24dp), LinearLayout.LayoutParams(dp(32), dp(32))); addView(balanceDot, LinearLayout.LayoutParams(0, dp(32), 1f)); addView(symbol(R.drawable.audio_speaker_icon_24dp), LinearLayout.LayoutParams(dp(32), dp(32))) })
            balanceValue = createStepper(card.body, getString(R.string.audio_sound_balance), { settings.balance.toFloat() }, { value -> update { copy(balance = value.roundToInt()) } }, 5f, -100f, 100f) { formatBalance(it.roundToInt()) }
            card.body.addView(MaterialButton(requireContext(), null, com.google.android.material.R.attr.borderlessButtonStyle).apply { text = getString(R.string.audio_sound_balance_center); minHeight = dp(48); setOnClickListener { update { copy(balance = 0) } } })
        }
    }

    private fun createPlayback() {
        simpleCard(binding.speedCard, R.drawable.audio_speed_icon_24dp, R.string.audio_sound_speed).also { card ->
            speedValue = TextView(requireContext()).apply { gravity = Gravity.CENTER; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall) }; card.body.addView(speedValue)
            speedOptions = MaterialButtonToggleGroup(requireContext()).apply { isSingleSelection = true; isSelectionRequired = true }
            listOf(.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed -> speedOptions.addView(optionButton(getString(R.string.audio_sound_speed_value, speed)).apply { tag = speed; setOnClickListener { update { copy(speed = speed) } } }) }
            card.body.addView(speedOptions); createStepper(card.body, getString(R.string.audio_sound_speed_fine), { settings.speed }, { value -> update { copy(speed = value) } }, .05f, .5f, 2f) { getString(R.string.audio_sound_speed_value, it) }
            card.body.addView(TextView(requireContext()).apply { text = getString(R.string.audio_sound_pitch_unchanged); setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall) })
        }
        simpleCard(binding.sleepCard, R.drawable.audio_timer_icon_24dp, R.string.audio_sound_sleep_heading).also { card ->
            val group = MaterialButtonToggleGroup(requireContext()).apply { isSingleSelection = true; isSelectionRequired = true }
            listOf(getString(R.string.audio_sleep_off) to (0 to false), getString(R.string.audio_sleep_minutes, 15) to (15 to false), getString(R.string.audio_sleep_minutes, 30) to (30 to false), getString(R.string.audio_sleep_minutes, 45) to (45 to false), getString(R.string.audio_sleep_minutes, 60) to (60 to false), getString(R.string.audio_sleep_end_track) to (0 to true)).forEach { (label, timer) -> val button = optionButton(label).apply { tag = timer; setOnClickListener { (activity as? AudioPlayerActivity)?.setSleepTimer(timer.first, timer.second, ::refreshStatus) } }; sleepButtons[timer] = button; group.addView(button) }
            card.body.addView(android.widget.HorizontalScrollView(requireContext()).apply { isHorizontalScrollBarEnabled = false; addView(group) })
            sleepActiveChip = Chip(requireContext()).apply {
                isCloseIconVisible = true; minHeight = dp(48); isVisible = false
                setOnCloseIconClickListener { (activity as? AudioPlayerActivity)?.setSleepTimer(0, false, ::refreshStatus) }
            }
            card.body.addView(sleepActiveChip)
        }
    }

    private data class EffectViews(val switch: MaterialSwitch, val ring: CircularProgressIndicator, val controls: LinearLayout)
    private data class CardViews(val header: LinearLayout, val body: LinearLayout)
    private fun effectCard(parent: LinearLayout, icon: Int, title: Int): EffectViews {
        val card = simpleCard(parent, icon, title); val ring = CircularProgressIndicator(requireContext()).apply { isIndeterminate = false; max = 100; indicatorSize = dp(48); trackThickness = dp(4) }
        val image = card.header.getChildAt(0) as ImageView; card.header.removeView(image); val frame = FrameLayout(requireContext()).apply { addView(ring, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER)); addView(image, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER)) }; card.header.addView(frame, 0)
        val toggle = MaterialSwitch(requireContext()).apply { contentDescription = getString(title) }; card.header.addView(toggle); return EffectViews(toggle, ring, card.body)
    }
    private fun simpleCard(parent: LinearLayout, icon: Int, title: Int): CardViews {
        val header = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL; addView(symbol(icon), LinearLayout.LayoutParams(dp(48), dp(48))); addView(TextView(context).apply { text = getString(title); setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        val body = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); addView(header); addView(body) }
        val card = MaterialCardView(requireContext()).apply { radius = dp(20).toFloat(); strokeWidth = 0; setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainer, com.google.android.material.R.attr.colorSurface)); addView(content) }
        parent.setPadding(dp(4), dp(4), dp(4), dp(4)); parent.addView(card); return CardViews(header, body)
    }
    private fun symbol(icon: Int) = ImageView(requireContext()).apply { setImageResource(icon); setPadding(dp(12), dp(12), dp(12), dp(12)); imageTintList = ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorPrimary, androidx.appcompat.R.attr.colorPrimary)); contentDescription = null }

    private fun createStepper(container: LinearLayout, label: String, getter: () -> Float, setter: (Float) -> Unit, step: Float, min: Float, max: Float, formatter: (Float) -> String): TextView {
        val value = TextView(requireContext()).apply { gravity = Gravity.CENTER; minWidth = dp(72); setPadding(dp(8), dp(6), dp(8), dp(6)); setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge); setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest, com.google.android.material.R.attr.colorSurface)) }
        val group = MaterialButtonToggleGroup(requireContext()).apply { addView(stepButton(false, getString(R.string.audio_sound_decrease, label)) { setter((getter() - step).coerceAtLeast(min)) }); addView(stepButton(true, getString(R.string.audio_sound_increase, label)) { setter((getter() + step).coerceAtMost(max)) }) }
        container.addView(LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56); addView(TextView(context).apply { text = label; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); addView(value, LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT)); addView(group) })
        value.tag = formatter; return value
    }
    private fun stepButton(plus: Boolean, description: String, action: () -> Unit) = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = if (plus) "+" else "−"; contentDescription = description; minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(48); setOnClickListener { action() } }
    private fun optionButton(label: String) = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { id = View.generateViewId(); text = label; minHeight = dp(48); setPadding(dp(8), 0, dp(8), 0) }

    private fun setFineTuneExpanded(expanded: Boolean, animate: Boolean) {
        fineTuneExpanded = expanded; if (animate && Motion.any) TransitionManager.beginDelayedTransition(binding.equalizerControls, AutoTransition().setDuration(250)); binding.fineTuneContent.isVisible = expanded
        ViewCompat.setStateDescription(binding.fineTuneToggle, getString(if (expanded) R.string.audio_sound_expanded else R.string.audio_sound_collapsed)); if (animate) binding.fineTuneToggle.announceForAccessibility(getString(if (expanded) R.string.audio_sound_fine_tune_expanded else R.string.audio_sound_fine_tune_collapsed))
    }
    private fun update(transform: AudioSettings.() -> AudioSettings) { settings = settings.transform().sanitized(); AudioSettingsStore.write(settings); (activity as? AudioPlayerActivity)?.applySoundSettings(::refreshStatus); render() }
    @Suppress("UNCHECKED_CAST") private fun renderValue(view: TextView, value: Float) { val formatter = view.tag as? (Float) -> String ?: return; view.text = formatter(value); ViewCompat.setStateDescription(view, view.text) }
    private fun render() {
        initializing = true; binding.equalizerEnabled.isChecked = settings.equalizerEnabled; binding.equalizerCurve.setGains(settings.bandGainsDb, true); if (settings.preset == EqualizerPreset.CUSTOM) addPreset(EqualizerPreset.CUSTOM)
        presetCards.forEach { (preset, card) -> selectPresetCard(card, preset == settings.preset) }; bandValues.forEachIndexed { index, view -> renderValue(view, settings.bandGainsDb[index]) }; renderValue(preGainValue, settings.preGainDb)
        bassSwitch.isChecked = settings.bassBoostEnabled; renderValue(bassValue, settings.bassBoostStrength.toFloat()); bassRing.progress = settings.bassBoostStrength / 10; virtualizerSwitch.isChecked = settings.virtualizerEnabled; renderValue(virtualizerValue, settings.virtualizerStrength.toFloat()); virtualizerRing.progress = settings.virtualizerStrength / 10
        normalizationSwitch.isChecked = settings.normalizationEnabled; renderValue(normalizationValue, settings.normalizationPreampDb); binding.skipSilence.isChecked = settings.skipSilence; binding.mono.isChecked = settings.mono; binding.resumeHeadphones.isChecked = settings.resumeOnHeadphones
        renderValue(balanceValue, settings.balance.toFloat()); balanceDot.translationX = dp(36) * settings.balance / 100f; speedValue.text = getString(R.string.audio_sound_speed_value, settings.speed); speedOptions.children().filterIsInstance<MaterialButton>().forEach { it.isChecked = (it.tag as Float) == settings.speed }
        binding.focusBehavior.check(if (settings.focusBehavior == AudioFocusBehavior.PAUSE) R.id.focusPause else R.id.focusDuck); binding.resetEqualizer.isVisible = settings.bandGainsDb.any { it != 0f }; renderEqualizerState(); initializing = false
    }
    private fun selectPresetCard(card: MaterialCardView, selected: Boolean) {
        card.isChecked = selected; card.findViewWithTag<View>(PRESET_CHECK_TAG)?.isVisible = selected; card.setCardBackgroundColor(themeColor(if (selected) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorSurfaceContainer, com.google.android.material.R.attr.colorSurface)); val from = card.radius; val to = dp(if (selected) 28 else 16).toFloat()
        if (!Motion.any || from == to) card.radius = to else ValueAnimator.ofFloat(from, to).apply { duration = 320; interpolator = FastOutSlowInInterpolator(); addUpdateListener { card.shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(it.animatedValue as Float).build() }; start() }
    }
    private fun showStatus(status: AudioPlayerActivity.SoundStatus) {
        soundStatus = status; renderEqualizerState(); setEnabledRecursively(binding.normalizationCard, status.inputGainAvailable); setEnabledRecursively(binding.balanceCard, status.usingDynamicsProcessing); binding.bassSection.isVisible = status.bassBoostAvailable; binding.virtualizerSection.isVisible = status.virtualizerAvailable; binding.effectsUnavailable.removeAllViews()
        if (!status.bassBoostAvailable) unavailableRow(R.string.audio_sound_bass_unavailable); if (!status.virtualizerAvailable) unavailableRow(R.string.audio_sound_virtualizer_unavailable)
        val timer = if (status.sleepActive) status.sleepMinutes to status.sleepAtEndOfTrack else 0 to false; sleepButtons.forEach { (key, button) -> button.isChecked = key == timer }
        sleepActiveChip.isVisible = status.sleepActive
        sleepActiveChip.text = if (status.sleepAtEndOfTrack) getString(R.string.audio_sleep_end_track) else getString(R.string.audio_sleep_remaining, status.sleepMinutes)
    }
    private fun unavailableRow(text: Int) { binding.effectsUnavailable.addView(TextView(requireContext()).apply { setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.audio_speaker_icon_24dp, 0, 0, 0); compoundDrawablePadding = dp(16); gravity = Gravity.CENTER_VERTICAL; minHeight = dp(56); setText(text); setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium) }) }
    internal fun refreshStatus() { if (_binding != null) (activity as? AudioPlayerActivity)?.querySoundStatus(::showStatus) }
    private fun renderEqualizerState() {
        val status = soundStatus; binding.effectStatus.text = when { status == null -> getString(R.string.audio_sound_checking); !status.equalizerAvailable -> getString(R.string.audio_sound_effect_unavailable); !settings.equalizerEnabled -> getString(R.string.audio_sound_off); settings.preset == EqualizerPreset.CUSTOM -> getString(R.string.audio_preset_custom); else -> getString(presetLabel(settings.preset)) }
        val available = status?.equalizerAvailable == true; binding.equalizerEnabled.isEnabled = available; val enabled = available && settings.equalizerEnabled; binding.equalizerCurve.isEnabled = enabled; binding.equalizerCurve.alpha = if (enabled) 1f else .38f; binding.equalizerOffOverlay.isVisible = available && !settings.equalizerEnabled
        presetCards.values.forEach { it.isEnabled = enabled; it.alpha = if (enabled) 1f else .38f }; binding.fineTuneToggle.isEnabled = enabled; binding.fineTuneContent.alpha = if (enabled) 1f else .38f; setEnabledRecursively(binding.preGainRow, enabled && status?.inputGainAvailable == true)
    }
    private fun setEnabledRecursively(view: View, enabled: Boolean) { view.isEnabled = enabled; if (view is ViewGroup) view.children().forEach { setEnabledRecursively(it, enabled) } }
    private fun presetLabel(preset: EqualizerPreset) = when (preset) { EqualizerPreset.FLAT -> R.string.audio_preset_flat; EqualizerPreset.BASS -> R.string.audio_preset_bass; EqualizerPreset.TREBLE -> R.string.audio_preset_treble; EqualizerPreset.VOCAL -> R.string.audio_preset_vocal; EqualizerPreset.ROCK -> R.string.audio_preset_rock; EqualizerPreset.POP -> R.string.audio_preset_pop; EqualizerPreset.JAZZ -> R.string.audio_preset_jazz; EqualizerPreset.CLASSICAL -> R.string.audio_preset_classical; EqualizerPreset.LOUDNESS_AT_NIGHT -> R.string.audio_preset_night; EqualizerPreset.CUSTOM -> R.string.audio_preset_custom }
    private fun bandLabel(hz: Int) = if (hz >= 1000) getString(R.string.audio_frequency_khz, hz / 1000) else getString(R.string.audio_frequency_hz, hz)
    private fun formatDb(value: Float) = getString(R.string.audio_sound_db, value)
    private fun formatBalance(value: Int) = when { value < 0 -> getString(R.string.audio_sound_balance_left, -value); value > 0 -> getString(R.string.audio_sound_balance_right, value); else -> getString(R.string.audio_sound_balance_center) }
    private fun themeColor(attr: Int, fallback: Int): Int { val out = TypedValue(); val found = requireContext().theme.resolveAttribute(attr, out, true) || requireContext().theme.resolveAttribute(fallback, out, true); return if (!found) Color.GRAY else if (out.resourceId != 0) requireContext().getColor(out.resourceId) else out.data }
    private fun ViewGroup.children(): Sequence<View> = sequence { for (index in 0 until childCount) yield(getChildAt(index)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    override fun onDestroyView() { _binding = null; super.onDestroyView() }
    companion object { const val TAG = "audioSoundSettings"; private const val PRESET_CHECK_TAG = "presetCheck" }
}

internal enum class EqualizerStatusKind { UNSUPPORTED, OFF, PRESET }
internal fun equalizerStatusKind(available: Boolean, enabled: Boolean) = when { !available -> EqualizerStatusKind.UNSUPPORTED; !enabled -> EqualizerStatusKind.OFF; else -> EqualizerStatusKind.PRESET }
internal fun equalizerControlsEnabled(available: Boolean, enabled: Boolean) = available && enabled
internal fun equalizerPresetRail(selected: EqualizerPreset): List<EqualizerPreset> =
    EqualizerPreset.entries.filter { it != EqualizerPreset.CUSTOM || selected == EqualizerPreset.CUSTOM }
