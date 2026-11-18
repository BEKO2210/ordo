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
import android.widget.LinearLayout
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
    onClose: () -> Unit,
    onPlayPause: () -> Unit
) : FrameLayout(context) {
    private val songTitle = TextView(context)
    private val songArtist = TextView(context)
    private val header = LinearLayout(context)
    private val playPause = ImageButton(context)
    private val bottomFade = View(context)
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
        // A header with the song over a fade, so lines scroll away beneath it instead of under the close button.
        val row = resources.getDimensionPixelSize(R.dimen.phi_55)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.updatePadding(right = resources.getDimensionPixelSize(R.dimen.phi_21),
            bottom = resources.getDimensionPixelSize(R.dimen.phi_21))
        header.addView(ImageButton(context).apply {
            setImageResource(R.drawable.close_icon_control_normal_24dp)
            contentDescription = context.getString(R.string.music_lyrics_close)
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setOnClickListener { onClose() }
        }, LinearLayout.LayoutParams(row, row))
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        songTitle.setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1)
        songTitle.maxLines = 1
        songTitle.ellipsize = android.text.TextUtils.TruncateAt.END
        songArtist.setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
        songArtist.maxLines = 1
        songArtist.ellipsize = android.text.TextUtils.TruncateAt.END
        texts.addView(songTitle)
        texts.addView(songArtist)
        header.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
        // Play and pause stay at hand while reading.
        val play = resources.getDimensionPixelSize(R.dimen.audio_player_play_control_size)
        playPause.setBackgroundResource(R.drawable.audio_player_primary_control_background)
        val padding = resources.getDimensionPixelSize(R.dimen.audio_player_play_control_padding)
        playPause.setPadding(padding, padding, padding, padding)
        playPause.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        playPause.setOnClickListener { onPlayPause() }
        addView(bottomFade, LayoutParams(LayoutParams.MATCH_PARENT, play + 2 * resources.getDimensionPixelSize(
            R.dimen.phi_34), Gravity.BOTTOM))
        addView(playPause, LayoutParams(play, play, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = resources.getDimensionPixelSize(R.dimen.phi_34)
        })
    }

    init {
        // The fades reach the screen's edges; only the header's content and the button keep clear of the system bars
        // (a padded layer left a seam beside the Tab's right inset).
        val headerRight = header.paddingRight
        val headerBottom = header.paddingBottom
        val buttonBottom = (playPause.layoutParams as LayoutParams).bottomMargin
        val fadeHeight = bottomFade.layoutParams.height
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            header.setPadding(bars.left, bars.top, headerRight + bars.right, headerBottom)
            (playPause.layoutParams as LayoutParams).bottomMargin = buttonBottom + bars.bottom
            bottomFade.layoutParams.height = fadeHeight + bars.bottom
            requestLayout()
            insets
        }
    }

    /** The song shown in the header. */
    fun setSong(title: CharSequence, artist: CharSequence) {
        songTitle.text = title
        songArtist.text = artist
    }

    /** The play button's state and colours (the player's cover accent). */
    fun setPlayback(showPlay: Boolean, accent: Int, onAccent: Int) {
        playPause.setImageResource(if (showPlay) R.drawable.play_icon_24dp else R.drawable.pause_icon_24dp)
        playPause.contentDescription = context.getString(
            if (showPlay) R.string.audio_player_play else R.string.audio_player_pause
        )
        ViewCompat.setBackgroundTintList(playPause, android.content.res.ColorStateList.valueOf(accent))
        androidx.core.widget.ImageViewCompat.setImageTintList(
            playPause, android.content.res.ColorStateList.valueOf(onAccent)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Room above the first and below the last line, so every line can stand in the middle; on a wide screen the
        // lines keep a readable width in the middle.
        val side = maxOf(resources.getDimensionPixelSize(R.dimen.phi_34), (w - (MAX_LINE_WIDTH_DP *
            resources.displayMetrics.density).toInt()) / 2)
        list.updatePadding(left = side, right = side, top = h / 2, bottom = h / 2)
        post { center(animate = false) }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun show(lyrics: Lyrics, textColor: Int, dimColor: Int, veil: Int) {
        this.lyrics = lyrics
        this.textColor = textColor
        this.dimColor = dimColor
        songTitle.setTextColor(textColor)
        songArtist.setTextColor(dimColor)
        // Fades in the backdrop's own veil colour, top and bottom, so the lines melt away under header and button.
        // Never fully opaque: the backdrop is the veil over the blurred cover, a solid band would stand out.
        val soft = androidx.core.graphics.ColorUtils.setAlphaComponent(veil, FADE_ALPHA)
        val clear = androidx.core.graphics.ColorUtils.setAlphaComponent(veil, 0)
        header.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(soft, soft, clear)
        )
        bottomFade.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(soft, soft, clear)
        )
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
        view.typeface = if (active) BOLD else MEDIUM
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
                typeface = MEDIUM
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
        private const val INACTIVE_SCALE = 0.8f
        private const val MAX_LINE_WIDTH_DP = 640
        private const val FADE_ALPHA = 0xb0
        private val MEDIUM = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        private val BOLD = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        private const val STYLE_MS = 250L
        private const val SMOOTH_MS_PER_INCH = 120f
        private const val USER_SCROLL_PAUSE_MS = 3_000L
    }
}
