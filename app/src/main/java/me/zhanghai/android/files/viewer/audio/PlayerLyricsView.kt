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
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
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
    // A RecyclerView: only the lines on screen have views, so even the longest file stays light (at most 5,000 lines,
    // see Lyrics).
    private val list = RecyclerView(context)
    private val layoutManager = LinearLayoutManager(context)
    private val adapter = LineAdapter()
    private var lyrics: Lyrics? = null
    private var lines: List<String> = emptyList()
    private var current = -1
    private var lastUserScroll = 0L
    private var textColor = 0
    private var dimColor = 0

    init {
        isClickable = true
        val side = resources.getDimensionPixelSize(R.dimen.phi_34)
        list.layoutManager = layoutManager
        list.adapter = adapter
        list.itemAnimator = null
        list.clipToPadding = false
        list.isVerticalScrollBarEnabled = false
        list.updatePadding(left = side, right = side)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) lastUserScroll = SystemClock.uptimeMillis()
            }
        })
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
        list.updatePadding(top = h / 2, bottom = h / 2)
        post { center(animate = false) }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun show(lyrics: Lyrics, textColor: Int, dimColor: Int) {
        this.lyrics = lyrics
        this.textColor = textColor
        this.dimColor = dimColor
        lines = if (lyrics.isSynced) lyrics.lines.map { it.text } else lyrics.text
        current = -1
        adapter.notifyDataSetChanged()
        layoutManager.scrollToPositionWithOffset(0, 0)
    }

    /** The playing position; only a change of line touches the views. */
    fun setPosition(positionMs: Long) {
        val lyrics = lyrics ?: return
        if (!lyrics.isSynced) return
        val line = lyrics.lineAt(positionMs)
        if (line == current) return
        val previous = current
        current = line
        if (previous >= 0) adapter.notifyItemChanged(previous, ACTIVE)
        if (line >= 0) adapter.notifyItemChanged(line, ACTIVE)
        center(Motion.any)
    }

    private fun center(animate: Boolean) {
        if (current < 0 || list.height == 0) return
        // The reader scrolled lately: leave the text where they put it.
        if (SystemClock.uptimeMillis() - lastUserScroll < USER_SCROLL_PAUSE_MS) return
        if (!animate) {
            val row = layoutManager.findViewByPosition(current)?.height ?: 0
            // The offset counts from the top padding (half the height), so -row / 2 puts the line's middle there.
            layoutManager.scrollToPositionWithOffset(current, -row / 2)
            return
        }
        list.layoutManager?.startSmoothScroll(object : LinearSmoothScroller(context) {
            override fun calculateDtToFit(
                viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int
            ): Int = (boxStart + boxEnd) / 2 - (viewStart + viewEnd) / 2

            override fun calculateSpeedPerPixel(displayMetrics: android.util.DisplayMetrics): Float =
                SMOOTH_MS_PER_INCH / displayMetrics.densityDpi
        }.apply { targetPosition = current })
    }

    private fun style(view: TextView, active: Boolean, animate: Boolean) {
        view.setTextColor(if (active) textColor else dimColor)
        val scale = if (active || lyrics?.isSynced != true) 1f else INACTIVE_SCALE
        if (animate) {
            view.animate().scaleX(scale).scaleY(scale).setDuration(STYLE_MS).start()
        } else {
            view.animate().cancel()
            view.scaleX = scale
            view.scaleY = scale
        }
    }

    private inner class LineAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = lines.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, LINE_TEXT_SP)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setLineSpacing(0f, 1.15f)
                val vertical = resources.getDimensionPixelSize(R.dimen.phi_8)
                updatePadding(top = vertical, bottom = vertical)
                pivotX = 0f
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val holder = object : RecyclerView.ViewHolder(view) {}
            view.setOnLongClickListener {
                val start = lyrics?.lines?.getOrNull(holder.bindingAdapterPosition)?.start?.toLong()
                    ?: return@setOnLongClickListener false
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                lastUserScroll = 0L
                onSeek(start)
                true
            }
            ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    if (lyrics?.isSynced == true) {
                        info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            AccessibilityNodeInfoCompat.ACTION_LONG_CLICK, context.getString(R.string.music_lyrics_jump)
                        ))
                    }
                }
            })
            return holder
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val view = holder.itemView as TextView
            view.text = lines[position].ifBlank { "♪" }
            view.isLongClickable = lyrics?.isSynced == true
            view.pivotY = 0f
            style(view, lyrics?.isSynced != true || position == current, false)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
            if (payloads.isEmpty()) return onBindViewHolder(holder, position)
            style(holder.itemView as TextView, position == current, Motion.any)
        }
    }

    companion object {
        private const val ACTIVE = "active"
        private const val LINE_TEXT_SP = 28f
        private const val INACTIVE_SCALE = 0.86f
        private const val STYLE_MS = 250L
        private const val SMOOTH_MS_PER_INCH = 120f
        private const val USER_SCROLL_PAUSE_MS = 3_000L
    }
}
