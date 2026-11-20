/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.KlangValueStepperBinding
import me.zhanghai.android.files.viewer.audio.KlangSheet

/**
 * "Bild" for the playing video, on the Klang sheet: brightness, contrast, saturation, colour temperature and
 * sharpness in steps −3…+3 (−/+ buttons of 48 dp, never a slider), live in the picture pass; deinterlacing automatic,
 * on or off; "Zurücksetzen". Kept per video where Ordo may remember ([PictureAdjustStore]).
 */
fun showPictureAdjustSheet(context: Context, picture: VideoPicture) {
    val sheet = KlangSheet(context, R.drawable.audio_tune_icon_24dp, R.string.video_picture_adjust_title)
    if (!picture.adjustable) sheet.info(context.getString(R.string.video_picture_adjust_unavailable))
    if (!picture.remembersAdjustments) sheet.info(context.getString(R.string.video_picture_adjust_not_remembered))
    val grid = sheet.grid()
    val card = sheet.card(R.drawable.audio_tune_icon_24dp, null, null, grid)

    val refreshers = ArrayList<() -> Unit>()
    fun stepRow(label: Int, get: (PictureAdjustments) -> Int, set: (PictureAdjustments, Int) -> PictureAdjustments) {
        val name = context.getString(label)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = context.resources.getDimensionPixelSize(R.dimen.klang_row_min_height)
            addView(TextView(context, null, 0, R.style.Widget_Ordo_Klang_Title).apply { text = name },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val stepper = KlangValueStepperBinding.inflate(LayoutInflater.from(context), row, false)
        row.addView(stepper.root, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        card.addView(row)
        for ((button, delta) in listOf(stepper.stepMinus to -1, stepper.stepPlus to 1)) {
            button.contentDescription = context.getString(
                if (delta < 0) R.string.video_picture_adjust_less_format else R.string.video_picture_adjust_more_format,
                name
            )
            button.setOnClickListener {
                val current = picture.adjustments
                picture.adjust(set(current, PictureAdjust.clampStep(get(current) + delta)))
                refreshers.forEach { it() }
            }
        }
        refreshers += {
            val step = get(picture.adjustments)
            stepper.stepValue.text = when {
                step > 0 -> "+$step"
                step < 0 -> "−${-step}"
                else -> "0"
            }
            stepper.stepValue.contentDescription = context.getString(R.string.video_picture_adjust_value_format, name, step)
            stepper.stepMinus.isEnabled = picture.adjustable && step > PictureAdjust.MIN_STEP
            stepper.stepPlus.isEnabled = picture.adjustable && step < PictureAdjust.MAX_STEP
        }
    }
    stepRow(R.string.video_picture_adjust_brightness, { it.brightness }) { a, v -> a.copy(brightness = v) }
    stepRow(R.string.video_picture_adjust_contrast, { it.contrast }) { a, v -> a.copy(contrast = v) }
    stepRow(R.string.video_picture_adjust_saturation, { it.saturation }) { a, v -> a.copy(saturation = v) }
    stepRow(R.string.video_picture_adjust_temperature, { it.temperature }) { a, v -> a.copy(temperature = v) }
    stepRow(R.string.video_picture_adjust_sharpness, { it.sharpness }) { a, v -> a.copy(sharpness = v) }
    val reset = sheet.textButton(
        card, context.getString(R.string.video_picture_adjust_reset), R.drawable.restart_icon_white_24dp
    ) {
        picture.adjust(PictureAdjustments())
        refreshers.forEach { it() }
    }
    refreshers += { reset.isEnabled = picture.adjustments != PictureAdjustments() }

    // Deinterlacing: automatic (what the file says), on, off.
    val deinterlaceCard = sheet.card(
        R.drawable.video_aspect_ratio_icon_24dp, context.getString(R.string.video_picture_deinterlace), null, grid
    )
    val choices = listOf(null, true, false)
    val segments = sheet.segments(
        deinterlaceCard, context.resources.getStringArray(R.array.video_picture_deinterlace_names).toList(),
        choices.indexOf(picture.adjustments.deinterlace).coerceAtLeast(0), 3
    ) { index ->
        picture.adjust(picture.adjustments.copy(deinterlace = choices[index]))
        refreshers.forEach { it() }
    }
    segments.forEach { it.isEnabled = picture.adjustable }
    val detected = sheet.info(
        context.getString(
            when (picture.deinterlaceDetected) {
                true -> R.string.video_picture_deinterlace_detected_interlaced
                false -> R.string.video_picture_deinterlace_detected_progressive
                null -> R.string.video_picture_deinterlace_detected_unknown
            }
        ),
        deinterlaceCard
    )
    refreshers += {
        val checked = choices.indexOf(picture.adjustments.deinterlace)
        segments.forEachIndexed { index, button -> button.isChecked = index == checked }
    }
    refreshers.forEach { it() }
    detected.isVisible = picture.adjustable

    sheet.show()
}
