/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.sound

import android.content.Context
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Player
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.KlangValueStepperBinding
import me.zhanghai.android.files.viewer.audio.KlangSheet
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
        val sheet = KlangSheet(context, R.drawable.audio_surround_icon_24dp, R.string.video_audio_track_title)
        val tracks = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { group -> (0 until group.length).map { group to it } }
        if (tracks.isEmpty()) sheet.info(context.getString(R.string.video_audio_track_none))
        val grid = sheet.grid()
        if (tracks.isNotEmpty()) {
            val card = sheet.card(R.drawable.audio_surround_icon_24dp, null, parent = grid)
            val locale = context.resources.configuration.locales[0]
            tracks.forEachIndexed { position, (group, index) ->
                val row = describe(
                    group.getTrackFormat(index).track(), position + 1, group.isTrackSupported(index), locale,
                    { context.getString(R.string.video_audio_track_unknown_language, it) },
                    { context.getString(R.string.video_audio_track_channels, it) },
                    context.getString(R.string.video_audio_track_unplayable)
                )
                val selected = group.isTrackSelected(index)
                sheet.row(
                    card, 0, row.title, row.detail, sheet.check(selected),
                    if (row.playable) {
                        {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                                .build()
                            sheet.dialog.dismiss()
                        }
                    } else null
                ).apply {
                    isSelected = selected
                    isEnabled = row.playable
                    // Dimmed, the text still keeps 3 : 1 against the card.
                    if (!row.playable) alpha = 0.6f
                }
            }
        }
        if (engine != null && tracks.isNotEmpty()) addSync(context, sheet, grid, engine)
        sheet.show()
    }

    private fun addSync(context: Context, sheet: KlangSheet, grid: ViewGroup, engine: VideoSoundEngine) {
        val card = sheet.card(
            R.drawable.audio_timer_icon_24dp, context.getString(R.string.video_audio_sync),
            context.getString(R.string.video_audio_sync_summary), grid
        )
        val stepper = KlangValueStepperBinding.inflate(android.view.LayoutInflater.from(context), card, true)
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
}
