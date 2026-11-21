/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.subtitles

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import me.zhanghai.android.files.R

/**
 * The sheet's live preview: a 16:9 card, left half light, right half dark, lines and distance at twice the size
 * they had on a picture this small
 * (light and dark: the two cases a style must pass), drawn by Media3's own SubtitleView in the chosen style.
 */
@OptIn(UnstableApi::class)
class SubtitlePreview(context: Context) : FrameLayout(context) {
    private val subtitleView = SubtitleView(context)

    init {
        val halves = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(View(context).apply { setBackgroundColor(LIGHT) }, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(View(context).apply { setBackgroundColor(DARK) }, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        addView(halves, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(subtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // TalkBack reads the preview once, by its description.
        subtitleView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        subtitleView.setCues(listOf(Cue.Builder().setText(context.getString(R.string.video_subtitles_preview_text)).build()))
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        setBackgroundResource(R.drawable.video_subtitles_preview_background)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun show(style: SubtitleStyle) {
        VideoSubtitles.applyStyle(subtitleView, style)
        subtitleView.setBottomPaddingFraction(style.bottomPercent / 100f)
        contentDescription = context.getString(R.string.video_subtitles_preview_description)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY))
        subtitleView.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight * ZOOM, MeasureSpec.EXACTLY)
        )
    }

    // The lower part of a ZOOM times taller picture: at the card's size the lines would be too small to judge.
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val w = right - left
        val h = bottom - top
        subtitleView.layout(0, -h * (ZOOM - 1), w, h)
    }

    private companion object {
        // A sunny sky and a night scene.
        const val LIGHT = 0xFFE9E4D8.toInt()
        const val DARK = 0xFF15171C.toInt()
        const val ZOOM = 2
    }
}
