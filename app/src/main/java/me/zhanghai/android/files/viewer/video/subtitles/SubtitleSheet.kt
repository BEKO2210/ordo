/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.subtitles

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import java.text.NumberFormat
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.KlangValueStepperBinding
import me.zhanghai.android.files.viewer.audio.KlangSheet
import me.zhanghai.android.files.viewer.video.VideoInfo

/**
 * "Subtitles" on the Klang sheet: Off, the files and the film's tracks; "Load subtitles…" through Ordo's picker; for a
 * file or a text track its delay (−1 s, −0.1 s, +0.1 s, +1 s, never a slider), for a file its charset; the look
 * for every film with a live preview: size and height in −/+ steps, font, edge and colour in segments, Android's
 * caption settings or an ASS file's own styles instead. Each row is at least 48 dp; text never breaks inside a word.
 */
fun showSubtitleSheet(context: Context, subtitles: VideoSubtitles) {
    val sheet = KlangSheet(context, R.drawable.video_subtitles_icon_24dp, R.string.video_subtitles_title)
    val dialog = sheet.dialog
    subtitles.dismissSheet = dialog::dismiss
    dialog.setOnDismissListener { subtitles.dismissSheet = null }
    val grid = sheet.grid()

    // Off, files, tracks.
    val current = subtitles.currentChoice()
    val choicesCard = sheet.card(R.drawable.video_subtitles_icon_24dp, null, null, grid)
    lateinit var delayCard: View
    lateinit var delaySteps: View
    lateinit var fileCard: View
    lateinit var trackNote: View
    lateinit var bitmapNote: View
    fun updateSections(choice: SubtitleChoice) {
        val bitmap = choice is SubtitleChoice.Track && VideoSubtitles.isBitmap(subtitles.trackFormat(choice))
        val delayShown = choice is SubtitleChoice.File || (choice is SubtitleChoice.Track && !bitmap)
        delayCard.isVisible = delayShown || bitmap
        delaySteps.isVisible = delayShown
        fileCard.isVisible = choice is SubtitleChoice.File
        trackNote.isVisible = bitmap
        bitmapNote.isVisible = bitmap
    }
    var trackNumber = 0
    val checks = ArrayList<ImageView>()
    val rows = ArrayList<View>()
    for (candidate in subtitles.choices()) {
        val choice = candidate.choice
        if (choice is SubtitleChoice.Track) trackNumber++
        val check = sheet.check(choice == current)
        val position = checks.size
        checks += check
        val icon = when (choice) {
            SubtitleChoice.Off -> R.drawable.video_subtitles_off_icon_24dp
            is SubtitleChoice.File -> R.drawable.file_document_icon
            is SubtitleChoice.Track -> R.drawable.video_subtitles_icon_24dp
        }
        rows += sheet.row(choicesCard, icon, label(context, subtitles, choice, trackNumber), null, check) {
            subtitles.choose(choice)
            checks.forEachIndexed { index, other ->
                other.visibility = if (index == position) View.VISIBLE else View.INVISIBLE
                rows[index].isSelected = index == position
            }
            updateSections(choice)
        }.apply { isSelected = choice == current }
    }
    sheet.textButton(choicesCard, context.getString(R.string.video_subtitles_load)) {
        dialog.dismiss()
        subtitles.pickFile()
    }

    // A file or a text track: the delay; a file: its charset.
    val delayColumn = sheet.card(
        R.drawable.audio_timer_icon_24dp, context.getString(R.string.video_subtitles_delay), null, grid
    )
    delayCard = delayColumn.parent as View
    val stepsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    delaySteps = stepsBox
    val delayValue = TextView(context, null, 0, R.style.Widget_Ordo_Klang_Value).apply {
        gravity = Gravity.CENTER
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    fun showDelay() {
        delayValue.text = formatSeconds(subtitles.delayMs, signed = true)
    }
    stepsBox.addView(delayValue, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ))
    val steps = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    val gap = context.resources.getDimensionPixelSize(R.dimen.klang_space_xs)
    for ((index, step) in longArrayOf(-1_000, -100, 100, 1_000).withIndex()) {
        val button = LayoutInflater.from(context).inflate(R.layout.klang_segment, steps, false) as MaterialButton
        button.apply {
            isCheckable = false
            text = formatSeconds(step, signed = true)
            contentDescription = context.getString(
                if (step < 0) R.string.video_subtitles_earlier_format else R.string.video_subtitles_later_format,
                formatSeconds(kotlin.math.abs(step), signed = false)
            )
            setOnClickListener {
                subtitles.changeDelay(step)
                showDelay()
            }
        }
        steps.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (index > 0) marginStart = gap
        })
    }
    stepsBox.addView(steps, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = gap })
    showDelay()
    delayColumn.addView(stepsBox)
    trackNote = sheet.info(context.getString(R.string.video_subtitles_delay_text_only), delayColumn)

    val charsetColumn = sheet.card(
        R.drawable.information_icon_white_24dp, context.getString(R.string.video_subtitles_charset), null, grid
    )
    fileCard = charsetColumn.parent as View
    val charsetNames = context.resources.getStringArray(R.array.video_subtitles_charset_names)
    val auto = context.getString(
        R.string.video_subtitles_charset_auto_format,
        subtitles.usedCharset?.takeIf { subtitles.charsetOverride == null } ?: "–"
    )
    sheet.segments(
        charsetColumn, listOf(auto) + charsetNames.toList(),
        // Two lines: "Westeuropäisch (Windows-1252)" and "(ISO-8859-15)" were both cut to "Westeuropäisch" (A8).
        (SubtitleFiles.CHARSETS.indexOf(subtitles.charsetOverride) + 1).coerceAtLeast(0), 2, tall = true
    ) { index ->
        subtitles.changeCharset(if (index == 0) null else SubtitleFiles.CHARSETS[index - 1])
    }

    // The look, for every film: a live preview over a light and a dark picture, then the steps.
    val styleColumn = sheet.card(
        R.drawable.audio_tune_icon_24dp, context.getString(R.string.video_subtitles_style), null, grid
    )
    bitmapNote = sheet.info(context.getString(R.string.video_subtitles_bitmap_note), styleColumn)
    updateSections(current)
    val preview = SubtitlePreview(context)
    styleColumn.addView(preview, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = context.resources.getDimensionPixelSize(R.dimen.klang_space_sm) })
    val ownCards = ArrayList<View>()
    fun ownCard(title: Int): LinearLayout =
        sheet.card(R.drawable.audio_tune_icon_24dp, context.getString(title), null, grid).also { ownCards += it.parent as View }
    val renders = ArrayList<() -> Unit>()
    fun update(change: (SubtitleStyle) -> SubtitleStyle) {
        subtitles.style = change(subtitles.style)
        renders.forEach { it() }
    }
    fun switchRow(title: Int, summary: Int, isOn: (SubtitleStyle) -> Boolean, flip: (SubtitleStyle) -> SubtitleStyle) {
        val switch = MaterialSwitch(context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val row = sheet.row(styleColumn, 0, context.getString(title), context.getString(summary), switch) {
            update(flip)
        }
        renders += {
            val on = isOn(subtitles.style)
            switch.isChecked = on
            ViewCompat.setStateDescription(
                row, context.getString(if (on) R.string.audio_sound_on else R.string.audio_sound_off)
            )
        }
    }
    switchRow(R.string.video_subtitles_style_system, R.string.video_subtitles_style_system_summary, { it.system }) {
        it.copy(system = !it.system)
    }
    switchRow(R.string.video_subtitles_style_file, R.string.video_subtitles_style_file_summary, { it.fileStyles }) {
        it.copy(fileStyles = !it.fileStyles)
    }

    fun stepper(column: LinearLayout, minus: Int, plus: Int, value: (SubtitleStyle) -> String,
                atMin: (SubtitleStyle) -> Boolean, atMax: (SubtitleStyle) -> Boolean, step: (SubtitleStyle, Int) -> SubtitleStyle) {
        val stepper = KlangValueStepperBinding.inflate(LayoutInflater.from(context), column, true)
        stepper.stepMinus.contentDescription = context.getString(minus)
        stepper.stepPlus.contentDescription = context.getString(plus)
        stepper.stepMinus.setOnClickListener { update { step(it, -1) } }
        stepper.stepPlus.setOnClickListener { update { step(it, 1) } }
        renders += {
            val style = subtitles.style
            stepper.stepValue.text = value(style)
            ViewCompat.setStateDescription(stepper.stepValue, value(style))
            stepper.stepMinus.isEnabled = !atMin(style)
            stepper.stepPlus.isEnabled = !atMax(style)
        }
    }
    val percent = NumberFormat.getPercentInstance()
    stepper(
        ownCard(R.string.video_subtitles_size), R.string.video_subtitles_size_smaller, R.string.video_subtitles_size_larger,
        { percent.format(it.sizePercent / 100.0) }, { it.sizePercent <= SubtitleStyle.SIZE_MIN },
        { it.sizePercent >= SubtitleStyle.SIZE_MAX }, SubtitleStyle::stepSize
    )
    fun choice(title: Int, names: Int, perRow: Int, checked: (SubtitleStyle) -> Int, pick: (SubtitleStyle, Int) -> SubtitleStyle): List<MaterialButton> {
        val buttons = sheet.segments(
            ownCard(title), context.resources.getStringArray(names).toList(), checked(subtitles.style), perRow
        ) { index -> update { pick(it, index) } }
        renders += { buttons.forEachIndexed { index, button -> button.isChecked = index == checked(subtitles.style) } }
        return buttons
    }
    choice(R.string.video_subtitles_font, R.array.video_subtitles_font_names, 2, { it.font.ordinal }) { style, index ->
        style.copy(font = SubtitleStyle.Font.values()[index])
    }.forEachIndexed { index, button ->
        // Each font's name in that font.
        button.typeface = android.graphics.Typeface.create(SubtitleStyle.Font.values()[index].family, android.graphics.Typeface.NORMAL)
    }
    choice(R.string.video_subtitles_background, R.array.video_subtitles_background_names, 3, { it.background.ordinal }) { style, index ->
        style.copy(background = SubtitleStyle.Background.values()[index])
    }
    choice(R.string.video_subtitles_color, R.array.video_subtitles_color_names, 2, { it.color.ordinal }) { style, index ->
        style.copy(color = SubtitleStyle.Color.values()[index])
    }.forEachIndexed { index, button ->
        // A dot of the colour itself, with a thin dark ring so white shows on a light sheet.
        val size = context.resources.getDimensionPixelSize(R.dimen.klang_space_md)
        button.icon = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(SubtitleStyle.Color.values()[index].argb)
            setStroke((context.resources.displayMetrics.density).toInt().coerceAtLeast(1), 0x61000000)
            setSize(size, size)
        }
        button.iconTint = null
        button.iconSize = size
        button.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
    }
    val positionColumn = sheet.card(
        R.drawable.audio_tune_icon_24dp, context.getString(R.string.video_subtitles_position),
        context.getString(R.string.video_subtitles_position_summary), grid
    )
    stepper(
        positionColumn, R.string.video_subtitles_position_lower, R.string.video_subtitles_position_higher,
        { percent.format(it.bottomPercent / 100.0) }, { it.bottomPercent <= SubtitleStyle.BOTTOM_MIN },
        { it.bottomPercent >= SubtitleStyle.BOTTOM_MAX }, SubtitleStyle::stepBottom
    )
    sheet.textButton(positionColumn, context.getString(R.string.video_subtitles_style_reset)) {
        update { SubtitleStyle() }
    }
    renders += {
        val style = subtitles.style
        // Android's caption settings decide size, font, edge and colour then.
        ownCards.forEach { it.isVisible = !style.system }
        preview.show(style)
    }
    renders.forEach { it() }

    sheet.show()
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
                kindName(VideoSubtitles.subtitleMimeType(format)),
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
