/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import android.content.Context
import android.text.Layout
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import me.zhanghai.android.files.R
import me.zhanghai.android.files.viewer.video.showExpanded

/**
 * "Bild" for the playing video: brightness, contrast, saturation, colour temperature and sharpness in steps −3…+3
 * (−/+ buttons of 48 dp, never a slider), live in the picture pass; deinterlacing automatic, on or off; "Zurücksetzen".
 * Kept per video where Ordo may remember ([PictureAdjustStore]).
 */
fun showPictureAdjustSheet(context: Context, picture: VideoPicture) {
    val dialog = BottomSheetDialog(context)
    val padding = context.resources.getDimensionPixelSize(R.dimen.phi_21)
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, padding)
    }
    fun note(resId: Int) = TextView(context).apply {
        setText(resId)
        noWordBreaks()
        setPadding(0, padding / 2, 0, 0)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2)
    }
    content.addView(TextView(context).apply {
        setText(R.string.video_picture_adjust_title)
        noWordBreaks()
        setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Headline6)
        ViewCompat.setAccessibilityHeading(this, true)
    })
    if (!picture.adjustable) content.addView(note(R.string.video_picture_adjust_unavailable))
    if (!picture.remembersAdjustments) content.addView(note(R.string.video_picture_adjust_not_remembered))

    val refreshers = ArrayList<() -> Unit>()
    fun stepRow(label: Int, get: (PictureAdjustments) -> Int, set: (PictureAdjustments, Int) -> PictureAdjustments) {
        val name = context.getString(label)
        val value = TextView(context).apply {
            gravity = Gravity.CENTER
            minWidth = context.dp(48)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body1)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        fun button(delta: Int) = MaterialButton(
            context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = if (delta < 0) "−" else "+"
            isAllCaps = false
            minHeight = context.dp(48)
            minWidth = context.dp(48)
            minimumWidth = context.dp(48)
            setPadding(0, 0, 0, 0)
            contentDescription = context.getString(
                if (delta < 0) R.string.video_picture_adjust_less_format else R.string.video_picture_adjust_more_format,
                name
            )
            setOnClickListener {
                val current = picture.adjustments
                picture.adjust(set(current, PictureAdjust.clampStep(get(current) + delta)))
                refreshers.forEach { it() }
            }
        }
        val minus = button(-1)
        val plus = button(1)
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(56)
            addView(TextView(context).apply {
                text = name
                noWordBreaks()
                setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body1)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(minus, LinearLayout.LayoutParams(context.dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(value, LinearLayout.LayoutParams(context.dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(plus, LinearLayout.LayoutParams(context.dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        refreshers += {
            val step = get(picture.adjustments)
            value.text = when {
                step > 0 -> "+$step"
                step < 0 -> "−${-step}"
                else -> "0"
            }
            value.contentDescription = context.getString(R.string.video_picture_adjust_value_format, name, step)
            minus.isEnabled = picture.adjustable && step > PictureAdjust.MIN_STEP
            plus.isEnabled = picture.adjustable && step < PictureAdjust.MAX_STEP
        }
        content.addView(row)
    }
    stepRow(R.string.video_picture_adjust_brightness, { it.brightness }) { a, v -> a.copy(brightness = v) }
    stepRow(R.string.video_picture_adjust_contrast, { it.contrast }) { a, v -> a.copy(contrast = v) }
    stepRow(R.string.video_picture_adjust_saturation, { it.saturation }) { a, v -> a.copy(saturation = v) }
    stepRow(R.string.video_picture_adjust_temperature, { it.temperature }) { a, v -> a.copy(temperature = v) }
    stepRow(R.string.video_picture_adjust_sharpness, { it.sharpness }) { a, v -> a.copy(sharpness = v) }

    // Deinterlacing: automatic (what the file says), on, off.
    content.addView(TextView(context).apply {
        setText(R.string.video_picture_deinterlace)
        noWordBreaks()
        setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle2)
        setPadding(0, padding, 0, padding / 3)
        ViewCompat.setAccessibilityHeading(this, true)
    })
    val choices = listOf(null, true, false)
    val chips = ChipGroup(context).apply {
        isSingleSelection = true
        isSelectionRequired = true
        context.resources.getStringArray(R.array.video_picture_deinterlace_names).forEachIndexed { index, label ->
            addView(Chip(context, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                isEnabled = picture.adjustable
                setOnClickListener {
                    picture.adjust(picture.adjustments.copy(deinterlace = choices[index]))
                    refreshers.forEach { it() }
                }
            })
        }
    }
    content.addView(chips)
    val detected = note(
        when (picture.deinterlaceDetected) {
            true -> R.string.video_picture_deinterlace_detected_interlaced
            false -> R.string.video_picture_deinterlace_detected_progressive
            null -> R.string.video_picture_deinterlace_detected_unknown
        }
    )
    content.addView(detected)
    refreshers += {
        (chips.getChildAt(choices.indexOf(picture.adjustments.deinterlace)) as Chip).isChecked = true
    }

    val reset = MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        setText(R.string.video_picture_adjust_reset)
        isAllCaps = false
        minHeight = context.dp(48)
        setOnClickListener {
            picture.adjust(PictureAdjustments())
            refreshers.forEach { it() }
        }
    }
    content.addView(reset)
    refreshers += { reset.isEnabled = picture.adjustments != PictureAdjustments() }
    refreshers.forEach { it() }
    detected.isVisible = picture.adjustable

    dialog.setContentView(NestedScrollView(context).apply { addView(content) })
    dialog.showExpanded()
}

private fun TextView.noWordBreaks() {
    hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
    breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
}

private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
