/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.sound

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Player
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.KlangValueStepperBinding
import me.zhanghai.android.files.viewer.video.audioCodecName
import java.util.Locale

/**
 * Ordo Video › Audio track: every sound track by language, codec and channels; one this device cannot decode is
 * marked and cannot be chosen. Below it the A/V sync in 50 ms steps (−/+, never a slider).
 */
object VideoAudioTrackSheet {
    /** What one row says; pure, so the wording is unit-tested. */
    internal data class Row(val title: String, val detail: String, val playable: Boolean)

    /** The parts of a Media3 Format a row needs (Format itself cannot be built in JVM tests). */
    internal data class Track(val language: String?, val label: String?, val sampleMimeType: String?, val channelCount: Int)

    private fun Format.track() = Track(language, label, sampleMimeType, channelCount)

    internal fun describe(
        format: Track,
        number: Int,
        playable: Boolean,
        displayLocale: Locale,
        trackWord: (Int) -> String,
        channelsWord: (Int) -> String,
        unplayableWord: String
    ): Row {
        val language = format.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
            ?.let { Locale.forLanguageTag(it).getDisplayName(displayLocale) }
            ?.takeIf { it.isNotBlank() }
            ?.replaceFirstChar { it.titlecase(displayLocale) }
        val title = listOfNotNull(language, format.label?.takeIf { it.isNotBlank() && it != language })
            .joinToString(" · ").ifEmpty { trackWord(number) }
        val codec = audioCodecName(format.sampleMimeType) ?: format.sampleMimeType?.substringAfter('/')?.uppercase()
        val channels = format.channelCount.takeIf { it > 0 }
            ?.let { VideoSoundMath.channelLayout(it) ?: channelsWord(it) }
        val detail = listOfNotNull(codec, channels, unplayableWord.takeIf { !playable }).joinToString(" · ")
        return Row(title, detail, playable)
    }

    fun show(context: Context, player: Player, engine: VideoSoundEngine?) {
        val dialog = BottomSheetDialog(context)
        val padding = context.resources.getDimensionPixelSize(R.dimen.klang_space_lg)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(heading(context, R.string.video_audio_track_title))
        val tracks = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { group -> (0 until group.length).map { group to it } }
        if (tracks.isEmpty()) {
            content.addView(info(context, context.getString(R.string.video_audio_track_none)))
        }
        val locale = context.resources.configuration.locales[0]
        tracks.forEachIndexed { position, (group, index) ->
            val row = describe(
                group.getTrackFormat(index).track(), position + 1, group.isTrackSupported(index), locale,
                { context.getString(R.string.video_audio_track_unknown_language, it) },
                { context.getString(R.string.video_audio_track_channels, it) },
                context.getString(R.string.video_audio_track_unplayable)
            )
            content.addView(trackRow(context, row, group.isTrackSelected(index)) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                    .build()
                dialog.dismiss()
            })
        }
        if (engine != null && tracks.isNotEmpty()) addSync(context, content, engine)
        // The player screen owns the dialog: it goes when the screen does.
        (context as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) = dialog.dismiss()
        })
        dialog.setContentView(content)
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    private fun addSync(context: Context, content: LinearLayout, engine: VideoSoundEngine) {
        content.addView(heading(context, R.string.video_audio_sync).apply {
            setPadding(0, context.resources.getDimensionPixelSize(R.dimen.klang_space_xl), 0, 0)
        })
        content.addView(info(context, context.getString(R.string.video_audio_sync_summary)))
        val stepper = KlangValueStepperBinding.inflate(android.view.LayoutInflater.from(context), content, true)
        stepper.stepMinus.contentDescription = context.getString(R.string.video_audio_sync_earlier_button)
        stepper.stepPlus.contentDescription = context.getString(R.string.video_audio_sync_later_button)
        fun render() {
            val delay = engine.delayMs
            val text = when {
                delay > 0 -> context.getString(R.string.video_audio_sync_later, delay)
                delay < 0 -> context.getString(R.string.video_audio_sync_earlier, -delay)
                else -> context.getString(R.string.video_audio_sync_zero)
            }
            stepper.stepValue.text = text
            ViewCompat.setStateDescription(stepper.stepValue, text)
            stepper.stepMinus.isEnabled = delay > -VideoSoundMath.DELAY_MAX_MS
            stepper.stepPlus.isEnabled = delay < VideoSoundMath.DELAY_MAX_MS
        }
        stepper.stepMinus.setOnClickListener { engine.setDelay(VideoSoundMath.stepDelay(engine.delayMs, -1)); render() }
        stepper.stepPlus.setOnClickListener { engine.setDelay(VideoSoundMath.stepDelay(engine.delayMs, 1)); render() }
        // A tap on the value puts it back in sync.
        stepper.stepValue.setOnClickListener { engine.setDelay(0); render() }
        render()
    }

    private fun heading(context: Context, text: Int) = TextView(context).apply {
        setText(text)
        setTextAppearance(R.style.TextAppearance_Ordo_Klang_Title)
        ViewCompat.setAccessibilityHeading(this, true)
    }

    private fun info(context: Context, text: String) = TextView(context, null, 0, R.style.Widget_Ordo_Klang_Info).apply {
        this.text = text
    }

    private fun trackRow(context: Context, row: Row, selected: Boolean, onClick: () -> Unit): View {
        val res = context.resources
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = res.getDimensionPixelSize(R.dimen.klang_row_min_height)
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            isEnabled = row.playable
            alpha = if (row.playable) 1f else 0.5f
            if (row.playable) setOnClickListener { onClick() }
            isSelected = selected
            addView(ImageView(context).apply {
                setImageResource(R.drawable.check_icon_control_normal_24dp)
                visibility = if (selected) View.VISIBLE else View.INVISIBLE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(res.getDimensionPixelSize(R.dimen.klang_icon_column), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context, null, 0, R.style.Widget_Ordo_Klang_Title).apply { text = row.title })
                addView(TextView(context, null, 0, R.style.Widget_Ordo_Klang_Info).apply { text = row.detail })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }
}
