/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import me.zhanghai.android.files.R
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.viewer.audio.lyrics.Lyrics

/**
 * The player's lyrics layer over its atmosphere. Synced lyrics: the line that plays stands large in the text colour,
 * the others smaller in the secondary colour (both already WCAG-checked against the backdrop's veil), and the playing
 * line glides to the middle unless the reader scrolled in the last few seconds. A long press on a synced line jumps
 * there (deliberate, as the seek bar's hold); TalkBack offers it as the line's long-click action. Unsynced lyrics are
 * plain text to scroll. Reduced motion: no glide, no scaling animation.
 */
@SuppressLint("ViewConstructor")
class PlayerLyricsView(
    context: Context,
    private val onSeek: (Long) -> Unit,
    onClose: () -> Unit
) : FrameLayout(context) {
    private val scroll = NestedScrollView(context)
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var lyrics: Lyrics? = null
    private var current = -1
    private var lastUserScroll = 0L
    private var textColor = 0
    private var dimColor = 0

    init {
        isClickable = true
        val side = resources.getDimensionPixelSize(R.dimen.phi_34)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        column.updatePadding(left = side, right = side)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        scroll.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_MOVE) lastUserScroll = SystemClock.uptimeMillis()
            false
        }
        addView(ImageButton(context).apply {
            setImageResource(R.drawable.close_icon_control_normal_24dp)
            contentDescription = context.getString(R.string.music_lyrics_close)
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setOnClickListener { onClose() }
        }, LayoutParams(resources.getDimensionPixelSize(R.dimen.phi_55),
            resources.getDimensionPixelSize(R.dimen.phi_55), Gravity.TOP or Gravity.START))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Room above the first and below the last line, so every line can stand in the middle.
        column.updatePadding(top = h / 2, bottom = h / 2)
        post { center(animate = false) }
    }

    fun show(lyrics: Lyrics, textColor: Int, dimColor: Int) {
        this.lyrics = lyrics
        this.textColor = textColor
        this.dimColor = dimColor
        current = -1
        column.removeAllViews()
        val lines = if (lyrics.isSynced) lyrics.lines.map { it.text } else lyrics.text
        lines.forEachIndexed { index, text -> column.addView(lineView(text, index, lyrics.isSynced)) }
        for (i in 0 until column.childCount) style(column.getChildAt(i) as TextView, !lyrics.isSynced, false)
        scroll.scrollTo(0, 0)
    }

    fun setColors(textColor: Int, dimColor: Int) {
        this.textColor = textColor
        this.dimColor = dimColor
        val synced = lyrics?.isSynced == true
        for (i in 0 until column.childCount) style(column.getChildAt(i) as TextView, !synced || i == current, false)
    }

    /** The playing position; only a change of line touches the views. */
    fun setPosition(positionMs: Long) {
        val lyrics = lyrics ?: return
        if (!lyrics.isSynced) return
        val line = lyrics.lineAt(positionMs)
        if (line == current) return
        val previous = current
        current = line
        (column.getChildAt(previous) as? TextView)?.let { style(it, false, Motion.any) }
        (column.getChildAt(line) as? TextView)?.let { style(it, true, Motion.any) }
        center(Motion.any)
    }

    private fun center(animate: Boolean) {
        val view = column.getChildAt(current) ?: return
        // The reader scrolled lately: leave the text where they put it.
        if (SystemClock.uptimeMillis() - lastUserScroll < USER_SCROLL_PAUSE_MS) return
        val target = (view.top + view.height / 2 - scroll.height / 2).coerceAtLeast(0)
        if (animate) scroll.smoothScrollTo(0, target, CENTER_MS) else scroll.scrollTo(0, target)
    }

    private fun lineView(text: String, index: Int, synced: Boolean) = TextView(context).apply {
        this.text = text.ifBlank { "♪" }
        setTextSize(TypedValue.COMPLEX_UNIT_SP, LINE_TEXT_SP)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setLineSpacing(0f, 1.15f)
        val vertical = resources.getDimensionPixelSize(R.dimen.phi_8)
        updatePadding(top = vertical, bottom = vertical)
        pivotX = 0f
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        if (synced) {
            setOnLongClickListener {
                val start = lyrics?.lines?.getOrNull(index)?.start?.toLong() ?: return@setOnLongClickListener false
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                lastUserScroll = 0L
                onSeek(start)
                true
            }
            ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        AccessibilityNodeInfoCompat.ACTION_LONG_CLICK,
                        context.getString(R.string.music_lyrics_jump)
                    ))
                }
            })
        }
    }

    private fun style(view: TextView, active: Boolean, animate: Boolean) {
        view.setTextColor(if (active) textColor else dimColor)
        val scale = if (active || lyrics?.isSynced != true) 1f else INACTIVE_SCALE
        view.pivotY = view.height / 2f
        if (animate) {
            view.animate().scaleX(scale).scaleY(scale).setDuration(STYLE_MS).start()
        } else {
            view.animate().cancel()
            view.scaleX = scale
            view.scaleY = scale
        }
    }

    companion object {
        private const val LINE_TEXT_SP = 28f
        private const val INACTIVE_SCALE = 0.86f
        private const val STYLE_MS = 250L
        private const val CENTER_MS = 500
        private const val USER_SCROLL_PAUSE_MS = 3_000L
    }
}
