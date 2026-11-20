/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import me.zhanghai.android.files.player.output.OutputPolicy
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
import me.zhanghai.android.files.databinding.KlangReverbRoomsBinding
import me.zhanghai.android.files.databinding.KlangValueStepperBinding
import me.zhanghai.android.files.databinding.KlangStepperRowBinding
import me.zhanghai.android.files.databinding.KlangSwitchRowBinding
import me.zhanghai.android.files.player.AudioFocusBehavior
import me.zhanghai.android.files.player.AudioSettings
import me.zhanghai.android.files.player.AudioSettingsStore
import me.zhanghai.android.files.player.LoudnessTarget
import me.zhanghai.android.files.player.EqualizerPreset
import me.zhanghai.android.files.player.ReverbPreset
import me.zhanghai.android.files.viewer.audio.KlangPictureView.Kind
import me.zhanghai.android.files.ui.Motion
import kotlin.math.roundToInt

class AudioSoundSettingsSheet : BottomSheetDialogFragment(),
    me.zhanghai.android.files.player.headphones.HeadphonesHost {
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
    private var highsRenderer: (() -> Unit)? = null
    private var bypassShown = false
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
        if (isSplit()) return splitPanes()
        // In landscape the sheet is as tall as the window and the column scrolls inside it.
        if (isLandscape()) {
            binding.root.layoutParams =
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        return binding.root
    }

    /** Landscape at 840 dp and wider: the equalizer on the left, the cards on the right, each scrolling on its own. */
    private fun isLandscape(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private fun isSplit(): Boolean = isLandscape() && resources.configuration.screenWidthDp >= SPLIT_MIN_DP

    /**
     * Moves the views of the one long column into two panes, 38.2 : 61.8 (golden ratio): curve, presets and fine-tune
     * on the left, effects, volume and playback on the right. The views stay the same objects, so the binding holds.
     */
    private fun splitPanes(): View {
        val context = requireContext()
        val content = binding.content
        val firstLower = content.indexOfChild(binding.effectsSection)
        val views = (0 until content.childCount).map { content.getChildAt(it) }
        content.removeAllViews()
        fun pane(children: List<View>) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, content.paddingBottom)
            children.forEach(::addView)
        }
        fun scroller(pane: View, weight: Float, id: Int) = androidx.core.widget.NestedScrollView(context).apply {
            this.id = id
            clipToPadding = false
            addView(pane)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
        }
        // The first card on the right needs no section gap above it.
        (binding.effectsSection.getChildAt(0).layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = 0
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // Full height, so each pane is bounded and scrolls (wrapping the content, nothing scrolled).
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(scroller(pane(views.subList(0, firstLower)), 0.382f, R.id.klangLeftPane))
            addView(scroller(pane(views.subList(firstLower, views.size)), 0.618f, R.id.klangRightPane))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        initializing = true
        firstRender = true
        soundStatus = null
        bypassShown = false
        renderers.clear()
        tiles.clear()
        sleepButtons.clear()
        settings = AudioSettingsStore.read()
        palette = resolvePalette()
        applyPalette()
        binding.close.setOnClickListener { dismiss() }
        if (savedInstanceState == null && arguments?.getBoolean(ARG_SLEEP_TIMER) == true) showSleepTimer()
        binding.fineTuneToggle.setOnClickListener { setFineTuneExpanded(!fineTuneExpanded, true) }
        setFineTuneExpanded(false, false)
        createPresets()
        snapPresets()
        createBandSteppers()
        bindStepper(
            binding.preGain, getString(R.string.audio_sound_pre_gain),
            { settings.preGainDb }, { update { copy(preGainDb = it) } }, 0.5f,
            AudioSettings.MIN_GAIN_DB, AudioSettings.MAX_GAIN_DB, ::formatDb
        )
        bindEffects()
        bindSwitchRow(
            binding.normalizationRow, R.drawable.audio_graphic_eq_icon_24dp, R.string.audio_sound_normalization,
            R.string.audio_sound_normalization_summary, { settings.normalizationEnabled },
            binding.normalizationPicture, Kind.NORMALIZE
        ) { update { copy(normalizationEnabled = it) } }
        bindStepper(
            binding.normalizationPreamp, getString(R.string.audio_sound_normalization_preamp),
            { settings.normalizationPreampDb }, { update { copy(normalizationPreampDb = it) } },
            0.5f, AudioSettings.MIN_GAIN_DB, AudioSettings.MAX_GAIN_DB, ::formatDb
        )
        renderers += ::renderPreamp
        // EBU R 128 s2: the broadcast reference or the louder level for noisy places (with the true-peak limiter).
        binding.loudnessTargetGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !initializing && !rendering) {
                update {
                    copy(
                        loudnessTarget = if (id == R.id.loudnessTargetOnTheGo) LoudnessTarget.ON_THE_GO
                        else LoudnessTarget.ORIGINAL
                    )
                }
            }
        }
        renderers += {
            binding.loudnessTargetGroup.check(
                if (settings.loudnessTarget == LoudnessTarget.ON_THE_GO) R.id.loudnessTargetOnTheGo
                else R.id.loudnessTargetOriginal
            )
        }
        bindSwitchRow(
            binding.monoRow, R.drawable.audio_hearing_icon_24dp, R.string.audio_sound_mono,
            R.string.audio_sound_mono_summary, { settings.mono }, binding.monoPicture, Kind.MONO
        ) { update { copy(mono = it) } }
        me.zhanghai.android.files.player.headphones.HeadphonesSheet.bindEntry(binding.headphonesEntry, this)
        renderers += {
            me.zhanghai.android.files.player.headphones.HeadphonesSheet.renderEntry(
                binding.headphonesEntry, settings, soundStatus?.quietBassDb
            )
        }
        bindSwitchRow(
            binding.skipRow, R.drawable.audio_fast_forward_icon_24dp, R.string.audio_sound_skip_silence,
            R.string.audio_sound_skip_silence_summary, { settings.skipSilence }, binding.skipPicture, Kind.SKIP_SILENCE
        ) { update { copy(skipSilence = it) } }
        bindSwitchRow(
            binding.headphonesRow, R.drawable.audio_headphones_icon_24dp, R.string.audio_sound_resume_headphones,
            R.string.audio_sound_headphones_summary, { settings.resumeOnHeadphones },
            binding.headphonesPicture, Kind.HEADPHONES
        ) { update { copy(resumeOnHeadphones = it) } }
        // setPreferredMixerAttributes exists from Android 14; the summary says that Ordo's stages are bypassed.
        binding.bitPerfectCard.isVisible = android.os.Build.VERSION.SDK_INT >= OutputPolicy.MIN_SDK
        bindSwitchRow(
            binding.bitPerfectRow, R.drawable.usb_icon_white_24dp, R.string.audio_sound_bit_perfect,
            R.string.audio_sound_bit_perfect_summary, { settings.bitPerfectUsb },
            binding.bitPerfectPicture, Kind.BIT_PERFECT
        ) { update { copy(bitPerfectUsb = it) } }
        setColumns()
        bindBalance()
        bindSpeed()
        bindFocus()
        createSleepOptions()

        binding.equalizerCurve.ringColor = palette.stage
        // The curve area keeps the golden ratio, width : height = φ : 1, within its bounds.
        binding.equalizerCurve.addOnLayoutChangeListener { curve, left, _, right, _, _, _, _, _ ->
            val width = right - left
            val target = (width / GOLDEN_RATIO).roundToInt().coerceIn(
                resources.getDimensionPixelSize(R.dimen.klang_curve_min_height),
                resources.getDimensionPixelSize(R.dimen.klang_curve_max_height)
            )
            if (width > 0 && curve.layoutParams.height != target) {
                curve.post { curve.layoutParams = curve.layoutParams.apply { height = target } }
            }
        }
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
        val split = isSplit()
        // Two panes take the whole window: the player's own close button behind a narrower sheet looked like a
        // second one, and the left pane needs the room.
        val maxWidth = if (split) resources.displayMetrics.widthPixels
        else resources.getDimensionPixelSize(R.dimen.klang_sheet_max_width)
        val width = minOf(resources.displayMetrics.widthPixels, maxWidth)
        sheet.layoutParams = sheet.layoutParams.apply {
            this.width = width
            // Two panes scroll inside a sheet of full height; one column scrolls inside the sheet as before.
            if (isLandscape()) height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        (sheet.layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.CENTER_HORIZONTAL
        val behavior = BottomSheetBehavior.from(sheet)
        // Material caps a bottom sheet at 640 dp; that made a narrow strip in a wide landscape window.
        behavior.maxWidth = width
        behavior.skipCollapsed = true
        // The sheet follows only one scrolling child; with two panes a drag would move the sheet instead of the
        // right pane. Closing stays with the close button and Back.
        if (isLandscape()) behavior.isDraggable = false
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
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
            binding.fineTuneCard, binding.bassCard.root, binding.crossfeedCard.root, binding.widthCard.root,
            binding.reverbCard.root, binding.warmthCard.root, binding.clarityCard.root, binding.highsCard.root,
            binding.normalizationCard, binding.balanceCard, binding.monoCard, binding.headphonePackCard,
            binding.speedCard, binding.sleepCard, binding.skipCard, binding.focusCard, binding.headphonesCard
        ).forEach { it.setCardBackgroundColor(palette.card) }
        binding.balanceView.setBackgroundSurface(palette.card)
        // The lower cards get a hairline: in the light theme the card and the sheet are almost the same colour.
        val outline = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOutlineVariant,
            MaterialColors.layer(palette.card, palette.onSurface, 0.16f)
        )
        listOf(
            binding.bassCard.root, binding.crossfeedCard.root, binding.widthCard.root, binding.reverbCard.root,
            binding.warmthCard.root, binding.clarityCard.root, binding.highsCard.root, binding.normalizationCard,
            binding.balanceCard,
            binding.monoCard, binding.headphonePackCard, binding.speedCard, binding.sleepCard, binding.skipCard, binding.focusCard,
            binding.headphonesCard
        ).forEach {
            it.strokeColor = outline
            it.strokeWidth = dp(1)
        }
    }

    /** Two columns of lower cards only when each one gets at least 320 dp; the sheet is that wide on tablets. */
    private fun setColumns() {
        val density = resources.displayMetrics.density
        // Two panes: the cards get 61.8 % of the wide sheet.
        val sheetDp = if (isSplit()) resources.configuration.screenWidthDp * 0.618f
        else minOf(
            resources.configuration.screenWidthDp.toFloat(),
            resources.getDimension(R.dimen.klang_sheet_max_width) / density
        )
        val contentDp = sheetDp - 2 * resources.getDimension(R.dimen.klang_space_lg) / density
        val gapDp = resources.getDimension(R.dimen.klang_card_gap) / density
        val minimumDp = resources.getDimension(R.dimen.klang_column_min_width) / density
        val columns = KlangMath.columns(contentDp, gapDp, minimumDp)
        binding.effectsGrid.columnCount = columns
        binding.playbackGrid.columnCount = columns
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
            tileBinding.root.layoutParams = LinearLayout.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.klang_tile_w),
                resources.getDimensionPixelSize(R.dimen.klang_tile_h)
            ).apply { marginEnd = resources.getDimensionPixelSize(R.dimen.klang_space_sm) }
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

    /** After a swipe the preset row settles on a whole tile at its left edge. */
    private fun snapPresets() {
        val scroller = binding.presets.parent as? HorizontalScrollView ?: return
        val settle = Runnable {
            val tiles = (0 until binding.presets.childCount).map { binding.presets.getChildAt(it) }.filter { it.isVisible }
            val target = tiles.minByOrNull { kotlin.math.abs(it.left - scroller.scrollX) }?.left ?: return@Runnable
            scroller.smoothScrollTo(target, 0)
        }
        scroller.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP ||
                event.actionMasked == android.view.MotionEvent.ACTION_CANCEL
            ) {
                scroller.removeCallbacks(settle)
                // After the fling has slowed down.
                scroller.postDelayed(settle, SNAP_DELAY_MS)
            }
            false
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
        val target = resources.getDimension(if (selected) R.dimen.klang_space_xl else R.dimen.klang_space_md)
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
            row.steps.stepMinus, row.steps.stepPlus,
            getString(R.string.audio_sound_decrease, label), getString(R.string.audio_sound_increase, label),
            { setter((getter() - step).coerceAtLeast(minimum)) },
            { setter((getter() + step).coerceAtMost(maximum)) }
        )
        renderers += {
            row.stepValue.text = formatter(getter())
            ViewCompat.setStateDescription(row.stepValue, row.stepValue.text)
        }
    }

    private fun bindStepButtons(
        minus: MaterialButton,
        plus: MaterialButton,
        decrease: String,
        increase: String,
        onMinus: () -> Unit,
        onPlus: () -> Unit
    ) {
        // A toggle group makes its members checkable; these are plain buttons.
        minus.isCheckable = false
        plus.isCheckable = false
        minus.contentDescription = decrease
        plus.contentDescription = increase
        minus.setOnClickListener { onMinus() }
        plus.setOnClickListener { onPlus() }
    }

    /** "−  value  +": the value chip sits between the buttons that change it. */
    private fun bindValueStepper(
        stepper: KlangValueStepperBinding,
        label: String,
        onMinus: () -> Unit,
        onPlus: () -> Unit
    ) {
        pill(stepper.stepValue)
        bindStepButtons(
            stepper.stepMinus, stepper.stepPlus,
            getString(R.string.audio_sound_decrease, label), getString(R.string.audio_sound_increase, label),
            onMinus, onPlus
        )
    }

    private fun showStepperValue(stepper: KlangValueStepperBinding, label: String, value: CharSequence) {
        stepper.stepValue.text = value
        stepper.stepValue.contentDescription = getString(R.string.audio_sound_picture, label, value)
    }

    /** Off: the controls of a setting stay visible but dimmed and untouchable. */
    private fun setControlsActive(view: View, active: Boolean) {
        setEnabledRecursively(view, active)
        view.alpha = if (active) 1f else OFF_ALPHA
    }

    private fun bindSwitchRow(
        row: KlangSwitchRowBinding,
        icon: Int,
        title: Int,
        summary: Int,
        getter: () -> Boolean,
        picture: KlangPictureView? = null,
        kind: Kind = Kind.MONO,
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
        picture?.kind = kind
        renderers += {
            row.toggle.isChecked = getter()
            picture?.setState(getter(), 1f)
            picture?.contentDescription = describe(title, onOff(getter()))
        }
    }

    private fun bindEffects() {
        val strength = R.string.audio_sound_strength
        val level = R.string.audio_sound_level
        bindEffectCard(
            binding.bassCard, R.drawable.audio_speaker_icon_24dp, R.string.audio_sound_bass_boost,
            R.string.audio_sound_bass_summary, Kind.BASS, strength, { settings.bassBoostEnabled },
            { update { copy(bassBoostEnabled = it) } }, { KlangMath.strengthFraction(settings.bassBoostStrength) },
            { getString(R.string.audio_sound_percent, KlangMath.strengthPercent(settings.bassBoostStrength)) },
            { update { copy(bassBoostStrength = (bassBoostStrength - STRENGTH_STEP).coerceAtLeast(0)) } },
            { update { copy(bassBoostStrength = (bassBoostStrength + STRENGTH_STEP).coerceAtMost(1_000)) } }
        )
        bindEffectCard(
            binding.crossfeedCard, R.drawable.audio_spatial_icon_24dp, R.string.audio_sound_crossfeed,
            R.string.audio_sound_crossfeed_summary, Kind.CROSSFEED, level, { settings.crossfeedEnabled },
            { update { copy(crossfeedEnabled = it) } }, { KlangMath.levelFraction(settings.crossfeedLevel) },
            { levelLabel(settings.crossfeedLevel) },
            { update { copy(crossfeedLevel = crossfeedLevel - 1) } },
            { update { copy(crossfeedLevel = crossfeedLevel + 1) } },
            greyedByMono = true
        )
        bindEffectCard(
            binding.widthCard, R.drawable.audio_width_icon_24dp, R.string.audio_sound_width,
            R.string.audio_sound_width_summary, Kind.WIDTH, R.string.audio_sound_width, { settings.widthEnabled },
            { update { copy(widthEnabled = it) } }, { KlangMath.widthFraction(settings.widthPercent) },
            { getString(R.string.audio_sound_percent, settings.widthPercent) },
            { update { copy(widthPercent = widthPercent - WIDTH_STEP) } },
            { update { copy(widthPercent = widthPercent + WIDTH_STEP) } },
            greyedByMono = true
        )
        bindEffectCard(
            binding.reverbCard, R.drawable.audio_surround_icon_24dp, R.string.audio_sound_reverb,
            R.string.audio_sound_reverb_summary, Kind.REVERB, R.string.audio_sound_reverb_mix,
            { settings.reverbEnabled }, { update { copy(reverbEnabled = it) } }, { settings.reverbMix / 100f },
            { getString(R.string.audio_sound_percent, settings.reverbMix) },
            { update { copy(reverbMix = reverbMix - MIX_STEP) } },
            { update { copy(reverbMix = reverbMix + MIX_STEP) } },
            pictureValue = { KlangMath.roomFraction(settings.reverbPreset) }
        )
        bindReverbRooms()
        bindEffectCard(
            binding.warmthCard, R.drawable.audio_warmth_icon_24dp, R.string.audio_sound_warmth,
            R.string.audio_sound_warmth_summary, Kind.WARMTH, level, { settings.warmthEnabled },
            { update { copy(warmthEnabled = it) } }, { KlangMath.levelFraction(settings.warmthLevel) },
            { levelLabel(settings.warmthLevel) },
            { update { copy(warmthLevel = warmthLevel - 1) } },
            { update { copy(warmthLevel = warmthLevel + 1) } }
        )
        bindEffectCard(
            binding.clarityCard, R.drawable.audio_clarity_icon_24dp, R.string.audio_sound_clarity,
            R.string.audio_sound_clarity_summary, Kind.CLARITY, level, { settings.clarityEnabled },
            { update { copy(clarityEnabled = it) } }, { KlangMath.levelFraction(settings.clarityLevel) },
            { levelLabel(settings.clarityLevel) },
            { update { copy(clarityLevel = clarityLevel - 1) } },
            { update { copy(clarityLevel = clarityLevel + 1) } }
        )
        highsRenderer = bindEffectCard(
            binding.highsCard, R.drawable.audio_highs_icon_24dp, R.string.audio_sound_highs,
            R.string.audio_sound_highs_summary, Kind.HIGHS, level, { settings.highsEnabled },
            { enabled ->
                update { copy(highsEnabled = enabled) }
                // The detector needs about two seconds of music before it can say what it found.
                if (enabled) handler.postDelayed({ refreshStatus() }, HIGHS_REFRESH_MS)
            },
            { settings.highsLevel / AudioSettings.HIGHS_LEVELS.toFloat() },
            {
                getString(
                    if (settings.highsLevel <= 1) R.string.audio_sound_level_light else R.string.audio_sound_level_medium
                )
            },
            { update { copy(highsLevel = highsLevel - 1) } },
            { update { copy(highsLevel = highsLevel + 1) } },
            note = {
                soundStatus?.let {
                    if (it.highsCutoffHz > 0) getString(R.string.audio_sound_highs_found, it.highsCutoffHz / 1_000f)
                    else getString(R.string.audio_sound_highs_none)
                }
            }
        )
    }

    /** Values are clamped by [AudioSettings.sanitized] in [update], so the steps never leave their range. */
    private fun bindEffectCard(
        card: KlangEffectCardBinding,
        icon: Int,
        title: Int,
        summary: Int,
        kind: Kind,
        stepLabel: Int,
        enabled: () -> Boolean,
        setEnabled: (Boolean) -> Unit,
        fraction: () -> Float,
        stateText: () -> String,
        onMinus: () -> Unit,
        onPlus: () -> Unit,
        greyedByMono: Boolean = false,
        pictureValue: () -> Float = fraction,
        note: (() -> String?)? = null
    ): () -> Unit {
        card.icon.setImageResource(icon)
        card.title.text = getString(title)
        card.summary.text = getString(summary)
        card.toggle.contentDescription = getString(title)
        card.picture.kind = kind
        card.toggle.setOnCheckedChangeListener { _, checked ->
            if (!initializing && !rendering) setEnabled(checked)
        }
        val label = getString(stepLabel)
        bindValueStepper(card.stepper, label, onMinus, onPlus)
        val renderer = {
            // Crossfeed and width have nothing to work on after the mono mix; the service skips them too.
            val greyed = greyedByMono && settings.mono
            val active = enabled() && !greyed
            if (greyedByMono) enableCard(card.root, !greyed)
            card.toggle.isChecked = enabled()
            card.ring.setProgressCompat((fraction() * 100).roundToInt(), Motion.any && !firstRender)
            card.ring.alpha = if (active) 1f else OFF_ALPHA
            val noteText = if (active) note?.invoke() else null
            card.state.isVisible = greyed || noteText != null
            card.state.text = if (greyed) getString(R.string.audio_sound_mono_greyed) else noteText
            showStepperValue(card.stepper, label, stateText())
            setControlsActive(card.stepper.root, active)
            setControlsActive(card.extra, active)
            card.picture.setState(active, pictureValue())
            card.picture.contentDescription = describe(title, if (active) stateText() else onOff(false))
        }
        renderers += renderer
        return renderer
    }

    private fun bindReverbRooms() {
        val rooms = KlangReverbRoomsBinding.inflate(layoutInflater, binding.reverbCard.extra, true)
        binding.reverbCard.extra.isVisible = true
        listOf(rooms.room0, rooms.room1, rooms.room2, rooms.room3).forEachIndexed { index, button ->
            val preset = ReverbPreset.entries[index]
            button.setOnClickListener { update { copy(reverbPreset = preset) } }
            // Two groups form one choice, so the checked state comes from the setting, never from a group.
            renderers += { button.isChecked = settings.reverbPreset == preset }
        }
    }

    private fun levelLabel(level: Int): String = getString(
        when (level) {
            1 -> R.string.audio_sound_level_light
            2 -> R.string.audio_sound_level_medium
            else -> R.string.audio_sound_level_strong
        }
    )

    private fun roomLabel(preset: ReverbPreset): Int = when (preset) {
        ReverbPreset.ROOM -> R.string.audio_reverb_room
        ReverbPreset.STUDIO -> R.string.audio_reverb_studio
        ReverbPreset.CLUB -> R.string.audio_reverb_club
        ReverbPreset.HALL -> R.string.audio_reverb_hall
    }

    private fun onOff(on: Boolean): String = getString(if (on) R.string.audio_sound_on else R.string.audio_sound_off)

    private fun describe(title: Int, state: CharSequence): String =
        getString(R.string.audio_sound_picture, getString(title), state)

    private fun bindBalance() {
        val label = getString(R.string.audio_sound_balance)
        bindValueStepper(
            binding.balanceStepper, label,
            { update { copy(balance = (balance - 5).coerceAtLeast(-100)) } },
            { update { copy(balance = (balance + 5).coerceAtMost(100)) } }
        )
        binding.balanceCenter.setOnClickListener { update { copy(balance = 0) } }
        renderers += {
            binding.balanceView.balance = settings.balance
            val value = formatBalance(settings.balance)
            showStepperValue(binding.balanceStepper, label, value)
            // Re-centring only means something off centre; the value chip already says "Mitte".
            binding.balanceCenter.isInvisible = settings.balance == 0
            ViewCompat.setStateDescription(binding.balanceView, value)
        }
    }

    private fun bindSpeed() {
        val format = NumberFormat.getInstance().apply { maximumFractionDigits = 2 }
        val buttons = listOf(
            binding.speedOption0, binding.speedOption1, binding.speedOption2,
            binding.speedOption3, binding.speedOption4
        )
        buttons.forEachIndexed { index, button ->
            button.text = "${format.format(KlangMath.SPEED_OPTIONS[index])}×"
            button.setOnClickListener { update { copy(speed = KlangMath.SPEED_OPTIONS[index]) } }
        }
        val label = getString(R.string.audio_sound_speed)
        bindValueStepper(
            binding.speedStepper, label,
            { update { copy(speed = roundSpeed((speed - 0.05f).coerceAtLeast(0.5f))) } },
            { update { copy(speed = roundSpeed((speed + 0.05f).coerceAtMost(2f))) } }
        )
        binding.speedPicture.kind = Kind.SPEED
        renderers += {
            val value = getString(R.string.audio_sound_speed_value, settings.speed)
            showStepperValue(binding.speedStepper, label, value)
            binding.speedPicture.tempo = settings.speed
            binding.speedPicture.setState(true, (settings.speed - 0.5f) / 1.5f)
            binding.speedPicture.contentDescription = describe(R.string.audio_sound_speed, value)
            // Two rows form one choice: the checked chip comes from the setting, never from a row.
            val selected = KlangMath.speedOptionIndex(settings.speed)
            buttons.forEachIndexed { index, button -> button.isChecked = index == selected }
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
        for ((button, icon) in listOf(
            binding.focusPause to R.drawable.audio_pause_icon_24dp, binding.focusDuck to R.drawable.audio_duck_icon_24dp
        )) {
            button.setIconResource(icon)
            button.iconSize = resources.getDimensionPixelSize(R.dimen.klang_choice_icon)
            button.iconPadding = resources.getDimensionPixelSize(R.dimen.klang_space_xs)
            button.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        }
        binding.focusPicture.kind = Kind.FOCUS
        renderers += {
            val pause = settings.focusBehavior == AudioFocusBehavior.PAUSE
            binding.focusBehavior.check(if (pause) R.id.focusPause else R.id.focusDuck)
            binding.focusPicture.setState(true, if (pause) 0f else 1f)
            binding.focusPicture.contentDescription = describe(
                R.string.audio_sound_focus_title,
                getString(if (pause) R.string.audio_sound_focus_pause else R.string.audio_sound_focus_duck)
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
            // In a narrow cell (tablet, two columns) the long label ended as "End of t…": the button shows the short
            // one, TalkBack and tests read the full one.
            if (timer.second) {
                button.text = getString(R.string.audio_sleep_end_track_short)
                button.contentDescription = label
            }
            // A small picture beside each choice (moon for a time, an end mark for the track), and the label shrinks
            // a little rather than being cut in a narrow cell.
            if (timer.first > 0 || timer.second) {
                button.setIconResource(if (timer.second) R.drawable.audio_track_end_icon_24dp else R.drawable.audio_moon_icon_24dp)
                button.iconSize = resources.getDimensionPixelSize(R.dimen.klang_choice_icon)
                button.iconPadding = resources.getDimensionPixelSize(R.dimen.klang_space_xs)
                button.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            }
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                button, 10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP
            )
            button.setOnClickListener {
                (activity as? AudioPlayerActivity)?.setSleepTimer(timer.first, timer.second, ::refreshStatus)
            }
            sleepButtons += button to timer
        }
        binding.sleepPicture.kind = Kind.SLEEP
        binding.sleepPicture.setState(false, 0f)
        binding.sleepPicture.contentDescription = describe(R.string.audio_sound_sleep_heading, onOff(false))
        binding.sleepRemaining.setOnClickListener { cancelSleep() }
        binding.sleepRemaining.setOnCloseIconClickListener { cancelSleep() }
        binding.sleepRemaining.closeIconContentDescription = getString(R.string.audio_sleep_cancel)
    }

    private fun cancelSleep() {
        (activity as? AudioPlayerActivity)?.setSleepTimer(0, false, ::refreshStatus)
    }

    override val headphoneSettings: AudioSettings get() = settings

    override val stagesBypassed: Boolean get() = soundStatus?.songBitPerfect == true

    override fun updateHeadphoneSettings(transform: AudioSettings.() -> AudioSettings) = update(transform)

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
                scroller?.scrollTo(maxOf(0, initialTile.binding.root.left - resources.getDimensionPixelSize(R.dimen.klang_space_lg)), 0)
            }
        }
        renderers.forEach { it() }
        renderEqualizerState()
        renderBypass()
        firstRender = false
        rendering = wasRendering
    }

    private fun showStatus(status: AudioPlayerActivity.SoundStatus) {
        soundStatus = status
        renderEqualizerState()
        renderPreamp()
        // Per-channel input gain is available in DynamicsProcessing, not the API 24-27 Equalizer.
        enableCard(binding.balanceCard, status.usingDynamicsProcessing)
        binding.effectsSection.isVisible = true
        showEffect(binding.bassCard.root, binding.bassUnavailable, status.bassBoostAvailable, R.string.audio_sound_bass_boost)
        // Clarity is the DynamicsProcessing multiband compressor (API 28+); without it the card gives way to a note.
        showEffect(
            binding.clarityCard.root, binding.clarityUnavailable, status.clarityAvailable, R.string.audio_sound_clarity
        )
        // A calm note instead of the summary when the DAC cannot take this song unchanged; its effects stay on.
        binding.bitPerfectRow.summary.text = getString(
            if (status.songNotBitPerfect) R.string.audio_sound_bit_perfect_song_not
            else R.string.audio_sound_bit_perfect_summary
        )
        // What the highs detector found arrives with the status.
        highsRenderer?.invoke()
        // The bass lift at this volume arrives with the status too.
        me.zhanghai.android.files.player.headphones.HeadphonesSheet.renderEntry(
            binding.headphonesEntry, settings, status.quietBassDb
        )
        val activeTimer = if (status.sleepActive) status.sleepMinutes to status.sleepAtEndOfTrack else 0 to false
        sleepButtons.forEach { (button, timer) -> button.isChecked = timer == activeTimer }
        val chip = binding.sleepRemaining
        chip.isVisible = status.sleepActive
        if (status.sleepActive) {
            chip.text = if (status.sleepAtEndOfTrack) getString(R.string.audio_sleep_until_end)
            else getString(R.string.audio_sleep_remaining, KlangMath.remainingMinutes(status.sleepRemainingMs))
        }
        binding.sleepPicture.setState(
            status.sleepActive,
            KlangMath.sleepFraction(status.sleepAtEndOfTrack, status.sleepMinutes, status.sleepRemainingMs)
        )
        binding.sleepPicture.contentDescription =
            describe(R.string.audio_sound_sleep_heading, if (status.sleepActive) chip.text else onOff(false))
        renderBypass()
    }

    /**
     * A bit-perfect song bypasses every stage: they show dimmed and untouchable (the saved values stay), the line at
     * the top says why. Focus, resuming on headphones, the sleep timer and the bit-perfect switch stay usable.
     */
    private fun renderBypass() {
        val bypassed = soundStatus?.songBitPerfect == true
        if (!bypassed && !bypassShown) return
        val ended = bypassShown && !bypassed
        bypassShown = bypassed
        listOf(
            binding.effectsSection, binding.normalizationCard, binding.balanceCard, binding.monoCard,
            binding.speedCard, binding.skipCard
        ).forEach { setControlsActive(it, !bypassed) }
        // Still opens, so the headphone sheet can say the same; only dimmed here.
        binding.headphonePackCard.alpha = if (bypassed) OFF_ALPHA else 1f
        childFragmentManager.fragments.filterIsInstance<me.zhanghai.android.files.player.headphones.HeadphonesSheet>().forEach { it.refresh() }
        if (ended) {
            // The per-setting states (mono greying, levelling controls, balance support) come back from the settings.
            render()
            soundStatus?.let { enableCard(binding.balanceCard, it.usingDynamicsProcessing) }
        }
    }

    /** Target and pre-amp only act while levelling is on (Ordo's own chain, on every device). */
    private fun renderPreamp() {
        setControlsActive(binding.normalizationPreamp.root, settings.normalizationEnabled)
        setControlsActive(binding.loudnessTargetGroup, settings.normalizationEnabled)
    }

    private fun showEffect(card: View, unavailable: TextView, available: Boolean, name: Int) {
        card.isVisible = available
        unavailable.isVisible = !available
        unavailable.text = getString(R.string.audio_sound_unavailable_row, getString(name))
    }

    private fun enableCard(view: View, enabled: Boolean) {
        setEnabledRecursively(view, enabled)
        view.alpha = if (enabled) 1f else OFF_ALPHA
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
        val bypassed = status?.songBitPerfect == true
        val kind = status?.let { equalizerStatusKind(it.equalizerAvailable, settings.equalizerEnabled, bypassed) }
        if (kind != null) {
            binding.effectStatus.text = when (kind) {
                EqualizerStatusKind.BYPASSED -> getString(R.string.audio_sound_bypassed)
                EqualizerStatusKind.UNSUPPORTED -> getString(R.string.audio_sound_effect_unavailable)
                EqualizerStatusKind.OFF -> getString(R.string.audio_sound_off)
                EqualizerStatusKind.PRESET ->
                    if (settings.preset == EqualizerPreset.CUSTOM) getString(R.string.audio_sound_status_custom)
                    else getString(presetLabel(settings.preset))
            }
        }
        // The off state is a quiet line above the dimmed curve, never a pill over its handles.
        val offText = when (kind) {
            EqualizerStatusKind.UNSUPPORTED -> getString(R.string.audio_sound_effect_unavailable)
            EqualizerStatusKind.OFF -> getString(R.string.audio_sound_curve_off)
            else -> null
        }
        binding.curveOffLabel.text = offText
        binding.curveOffLabel.isInvisible = offText == null // keeps its line: the curve does not jump
        setEnabledRecursively(binding.equalizerEnabled, status?.equalizerAvailable == true && !bypassed)
        val controlsEnabled = status?.let {
            equalizerControlsEnabled(it.equalizerAvailable, settings.equalizerEnabled, bypassed)
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

    /** Opened for the sleep timer (Ordo Music's menu): the sheet opens fully and scrolls to it. */
    private fun showSleepTimer() {
        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)?.behavior?.state =
            com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        val scrollView = binding.root
        scrollView.post {
            val rect = android.graphics.Rect()
            binding.sleepCard.getDrawingRect(rect)
            scrollView.offsetDescendantRectToMyCoords(binding.sleepCard, rect)
            scrollView.smoothScrollTo(0, rect.top)
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacks(sleepTicker)
        tiles.forEach { it.spring?.cancel() }
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "audioSoundSettings"
        const val ARG_SLEEP_TIMER = "sleepTimer"
        private const val DISABLED_ALPHA = 0.38f
        private const val GOLDEN_RATIO = 1.618f
        private const val STRENGTH_STEP = 50
        private const val WIDTH_STEP = 10
        private const val MIX_STEP = 5
        private const val HIGHS_REFRESH_MS = 2_500L
        private const val SNAP_DELAY_MS = 350L
        private const val SPLIT_MIN_DP = 840
        /** Off or held off in the lower sheet: dimmed, but graphic and value keep at least 3 : 1 against the card. */
        private const val OFF_ALPHA = 0.6f
        private const val SLEEP_REFRESH_MS = 30_000L
    }
}

internal enum class EqualizerStatusKind { BYPASSED, UNSUPPORTED, OFF, PRESET }

/** [bypassed]: the song plays bit-perfect, so the equalizer is not in the path whatever its setting. */
internal fun equalizerStatusKind(available: Boolean, enabled: Boolean, bypassed: Boolean = false): EqualizerStatusKind =
    when {
        bypassed -> EqualizerStatusKind.BYPASSED
        !available -> EqualizerStatusKind.UNSUPPORTED
        !enabled -> EqualizerStatusKind.OFF
        else -> EqualizerStatusKind.PRESET
    }

internal fun equalizerControlsEnabled(available: Boolean, enabled: Boolean, bypassed: Boolean = false): Boolean =
    available && enabled && !bypassed
