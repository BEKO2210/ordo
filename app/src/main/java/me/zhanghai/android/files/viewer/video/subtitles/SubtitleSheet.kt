/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.subtitles

import android.content.Context
import android.text.Layout
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.radiobutton.MaterialRadioButton
import java.text.NumberFormat
import me.zhanghai.android.files.R
import me.zhanghai.android.files.viewer.video.VideoInfo
import me.zhanghai.android.files.viewer.video.showExpanded

/**
 * "Subtitles": Off, the files and the film's tracks; "Load subtitles…" through Ordo's picker; for a file or a text
 * track its delay (−1 s, −0.1 s, +0.1 s, +1 s, never a slider), for a file its charset; the look in steps for every film. Each row is at least
 * 48 dp; text never breaks inside a word.
 */
fun showSubtitleSheet(context: Context, subtitles: VideoSubtitles) {
    val dialog = BottomSheetDialog(context)
    subtitles.dismissSheet = dialog::dismiss
    dialog.setOnDismissListener { subtitles.dismissSheet = null }
    val padding = context.resources.getDimensionPixelSize(R.dimen.phi_21)
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, padding)
    }
    fun text(resId: Int, heading: Boolean = false) = TextView(context).apply {
        setText(resId)
        noWordBreaks()
        if (heading) {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Headline6)
            ViewCompat.setAccessibilityHeading(this, true)
        } else {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle2)
            setPadding(0, padding, 0, padding / 3)
            ViewCompat.setAccessibilityHeading(this, true)
        }
    }

    content.addView(text(R.string.video_subtitles_title, heading = true))

    // Off, files, tracks.
    val current = subtitles.currentChoice()
    val radios = RadioGroup(context)
    lateinit var delaySection: View
    lateinit var fileSection: View
    lateinit var trackNote: View
    lateinit var bitmapNote: View
    fun updateSections(choice: SubtitleChoice) {
        val bitmap = choice is SubtitleChoice.Track && VideoSubtitles.isBitmap(subtitles.trackFormat(choice))
        delaySection.isVisible = choice is SubtitleChoice.File || (choice is SubtitleChoice.Track && !bitmap)
        fileSection.isVisible = choice is SubtitleChoice.File
        trackNote.isVisible = bitmap
        bitmapNote.isVisible = bitmap
    }
    var trackNumber = 0
    for (candidate in subtitles.choices()) {
        val choice = candidate.choice
        if (choice is SubtitleChoice.Track) trackNumber++
        radios.addView(MaterialRadioButton(context).apply {
            id = View.generateViewId()
            minHeight = context.dp(48)
            noWordBreaks()
            text = label(context, subtitles, choice, trackNumber)
            isChecked = choice == current
            setOnClickListener {
                subtitles.choose(choice)
                updateSections(choice)
            }
        })
    }
    content.addView(radios)
    content.addView(MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        setText(R.string.video_subtitles_load)
        isAllCaps = false
        minHeight = context.dp(48)
        setOnClickListener {
            dialog.dismiss()
            subtitles.pickFile()
        }
    })

    // A file or a text track: the delay; a file: its charset.
    val delayBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    delayBox.addView(text(R.string.video_subtitles_delay))
    val delayValue = TextView(context).apply {
        gravity = android.view.Gravity.CENTER
        minWidth = context.dp(72)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body1)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    fun showDelay() {
        delayValue.text = formatSeconds(subtitles.delayMs, signed = true)
    }
    val steps = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
    for (step in longArrayOf(-1_000, -100, 0, 100, 1_000)) {
        if (step == 0L) {
            steps.addView(delayValue, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f))
            continue
        }
        steps.addView(
            MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = formatSeconds(step, signed = true)
                isAllCaps = false
                minHeight = context.dp(48)
                minWidth = context.dp(48)
                minimumWidth = context.dp(48)
                setPadding(0, 0, 0, 0)
                contentDescription = context.getString(
                    if (step < 0) R.string.video_subtitles_earlier_format else R.string.video_subtitles_later_format,
                    formatSeconds(kotlin.math.abs(step), signed = false)
                )
                setOnClickListener {
                    subtitles.changeDelay(step)
                    showDelay()
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = context.dp(4)
                marginEnd = context.dp(4)
            }
        )
    }
    showDelay()
    delayBox.addView(steps)
    delaySection = delayBox
    content.addView(delayBox)
    val fileBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fileBox.addView(text(R.string.video_subtitles_charset))
    val charsetNames = context.resources.getStringArray(R.array.video_subtitles_charset_names)
    val auto = context.getString(
        R.string.video_subtitles_charset_auto_format,
        subtitles.usedCharset?.takeIf { subtitles.charsetOverride == null } ?: "–"
    )
    fileBox.addView(chips(
        context, listOf(auto) + charsetNames.toList(),
        (SubtitleFiles.CHARSETS.indexOf(subtitles.charsetOverride) + 1).coerceAtLeast(0)
    ) { index ->
        subtitles.changeCharset(if (index == 0) null else SubtitleFiles.CHARSETS[index - 1])
    })
    fileSection = fileBox
    content.addView(fileBox)
    trackNote = TextView(context).apply {
        setText(R.string.video_subtitles_delay_text_only)
        noWordBreaks()
        setPadding(0, padding / 2, 0, 0)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2)
    }
    content.addView(trackNote)

    // The look, for every film.
    bitmapNote = TextView(context).apply {
        setText(R.string.video_subtitles_bitmap_note)
        noWordBreaks()
        setPadding(0, padding, 0, 0)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2)
    }
    content.addView(bitmapNote)
    updateSections(current)
    val style = subtitles.style
    content.addView(text(R.string.video_subtitles_size))
    content.addView(chips(
        context, context.resources.getStringArray(R.array.video_subtitles_size_names).toList(), style.size.ordinal
    ) { subtitles.style = subtitles.style.copy(size = SubtitleStyle.Size.values()[it]) })
    content.addView(text(R.string.video_subtitles_background))
    content.addView(chips(
        context, context.resources.getStringArray(R.array.video_subtitles_background_names).toList(),
        style.background.ordinal
    ) { subtitles.style = subtitles.style.copy(background = SubtitleStyle.Background.values()[it]) })
    content.addView(text(R.string.video_subtitles_color))
    content.addView(chips(
        context, context.resources.getStringArray(R.array.video_subtitles_color_names).toList(), style.color.ordinal
    ) { subtitles.style = subtitles.style.copy(color = SubtitleStyle.Color.values()[it]) })

    dialog.setContentView(NestedScrollView(context).apply { addView(content) })
    dialog.showExpanded()
}

private fun label(context: Context, subtitles: VideoSubtitles, choice: SubtitleChoice, trackNumber: Int): String =
    when (choice) {
        SubtitleChoice.Off -> context.getString(R.string.video_subtitles_off)
        is SubtitleChoice.File -> {
            val file = subtitles.file(choice)
            listOfNotNull(
                VideoInfo.language(file?.language),
                context.getString(R.string.video_subtitles_forced).takeIf { file?.forced == true },
                file?.fileName
            ).joinToString(" · ")
        }
        is SubtitleChoice.Track -> {
            val format = subtitles.trackFormat(choice)
            listOfNotNull(
                VideoInfo.language(format?.language) ?: format?.label
                    ?: context.getString(R.string.video_subtitles_track_format, trackNumber),
                format?.label?.takeIf { VideoInfo.language(format.language) != null },
                kindName(format?.sampleMimeType),
                context.getString(R.string.video_subtitles_forced)
                    .takeIf { format != null && format.selectionFlags and androidx.media3.common.C.SELECTION_FLAG_FORCED != 0 }
            ).joinToString(" · ")
        }
    }

/** SRT, ASS, WebVTT … as people know them; null for anything unusual. */
private fun kindName(mimeType: String?): String? = when (mimeType) {
    "application/x-subrip" -> "SRT"
    "text/x-ssa" -> "ASS"
    "text/vtt", "application/x-mp4-vtt" -> "WebVTT"
    "application/ttml+xml" -> "TTML"
    "application/x-quicktime-tx3g" -> "TX3G"
    "application/pgs" -> "PGS"
    "application/vobsub" -> "VobSub"
    "application/dvbsubs" -> "DVB"
    "application/cea-608", "application/cea-708" -> "CC"
    else -> null
}

private fun chips(context: Context, labels: List<String>, checked: Int, onPick: (Int) -> Unit): ChipGroup =
    ChipGroup(context).apply {
        isSingleSelection = true
        isSelectionRequired = true
        labels.forEachIndexed { index, label ->
            addView(Chip(context, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                isChecked = index == checked
                setOnClickListener { onPick(index) }
            })
        }
    }

/** "+0,3 s", "−1 s", "0 s" in the device's number format. */
private fun formatSeconds(ms: Long, signed: Boolean): String {
    val number = NumberFormat.getInstance().apply { maximumFractionDigits = 1 }.format(kotlin.math.abs(ms) / 1000.0)
    val sign = when {
        !signed || ms == 0L -> ""
        ms < 0 -> "−"
        else -> "+"
    }
    return "$sign$number s"
}

private fun TextView.noWordBreaks() {
    hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
    breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
}

private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
