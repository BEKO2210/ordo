/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.seek

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.marginTop
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.extractor.metadata.Chapter
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.net.URI
import java.util.Formatter
import java8.nio.file.Paths
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.provider.linux.media.MediaScanner
import me.zhanghai.android.files.viewer.video.vault.VaultVideos
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.VideoPlayerActivityBinding
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.viewer.audio.KlangSheet

/**
 * Pro seeking for Ordo Video, kept apart from the player screen: preview frames while the seek bar is dragged or the
 * picture swiped, chapters (marks, sheet, name in the preview), frame by frame, A-B loop, "Save frame", pinch to zoom
 * and pan, and keyboard/remote keys. The screen only forwards touches, keys and the swipe-seek preview to it.
 * See docs/VIDEO-PLAYER.md "Profi-Spulen".
 */
@OptIn(UnstableApi::class)
internal class VideoSeekPro(
    private val activity: AppCompatActivity,
    private val binding: VideoPlayerActivityBinding,
    /** The entries of ⋮ › More (VideoMoreSheet), where chapters, frame by frame, A-B and save frame go. */
    private val moreMenu: Menu,
    private val player: ExoPlayer,
    private val playPause: () -> Unit,
    private val seekBy: (Long) -> Unit,
    private val jumpMs: () -> Long,
    private val toggleControls: () -> Unit,
    /** Locked: no pinch and no keys. */
    private val isLocked: () -> Boolean,
    private val showHint: (String) -> Unit
) {
    private val context = activity
    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val timeBuilder = StringBuilder()
    private val timeFormatter = Formatter(timeBuilder)
    private val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600
    private val thumbWidthPx = ((if (isTablet) 220 else 160) * density).toInt()
    private val thumbnails = SeekThumbnails(context, thumbWidthPx)
    private val marks = SeekBarMarks(
        binding.seekBar,
        com.google.android.material.color.MaterialColors.getColor(binding.seekBar, androidx.appcompat.R.attr.colorPrimary)
    )

    private var chapters = emptyList<SeekLogic.Chapter>()
    private var ab = SeekLogic.AbRepeat()
    private var abMessage: PlayerMessage? = null
    private var scrubbing = false
    private var wantedThumbMs = -1L
    private var lastFraction = 0f

    // Preview card: frame on top, chapter name below; neutral like the hint pill.
    private val previewImage = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.BLACK)
    }
    private val previewChapter = TextView(context).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        maxLines = 2
        hyphenationFrequencyCompat()
        val pad = (4 * density).toInt()
        setPadding(pad, pad, pad, 0)
    }
    private val preview = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.audio_player_seek_bubble)
        backgroundTintList = android.content.res.ColorStateList.valueOf(0xCC202020.toInt())
        val pad = (4 * density).toInt()
        setPadding(pad, pad, pad, pad)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isVisible = false
        addView(previewImage, LinearLayout.LayoutParams(thumbWidthPx, thumbWidthPx * 9 / 16))
        addView(previewChapter, LinearLayout.LayoutParams(thumbWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // Frame by frame: a row under the title bar, part of the controls.
    private var frameMode = false
    // Back first ends frame by frame (like closing its bar), then leaves the player.
    private val frameBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setFrameMode(false)
    }.also { activity.onBackPressedDispatcher.addCallback(activity, it) }
    private val frameLabel = TextView(context).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle2)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        minWidth = (96 * density).toInt()
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val frameBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.audio_player_seek_bubble)
        backgroundTintList = android.content.res.ColorStateList.valueOf(0xCC202020.toInt())
        isVisible = false
        addView(controlButton(R.drawable.video_seek_frame_back_icon_24dp, R.string.video_seek_frame_back) {
            frameStep(false)
        })
        addView(frameLabel)
        addView(controlButton(R.drawable.video_seek_frame_ahead_icon_24dp, R.string.video_seek_frame_ahead) {
            frameStep(true)
        })
        addView(controlButton(R.drawable.close_icon_white_24dp, R.string.video_seek_frame_close) {
            setFrameMode(false)
        })
    }

    // Zoom: the picture view grows (layout, not a view scale: SurfaceView follows layout and translation reliably).
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var pinching = false
    private var pinchMoved = false
    private var pinchDownTime = 0L
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var startSpan = 0f
    private var startFocusX = 0f
    private var startFocusY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            setZoom(zoom * detector.scaleFactor)
            return true
        }
    })
    private val zoomPill = TextView(context).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle2)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        minHeight = (48 * density).toInt()
        minWidth = (64 * density).toInt()
        val pad = (16 * density).toInt()
        setPadding(pad, 0, pad, 0)
        setBackgroundResource(R.drawable.audio_player_seek_bubble)
        backgroundTintList = android.content.res.ColorStateList.valueOf(0xCC202020.toInt())
        isVisible = false
        setOnClickListener { resetZoom() }
    }

    private val chaptersItem: MenuItem
    private val abItem: MenuItem
    private val audioManager by lazy { context.getSystemService<AudioManager>() }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            marks.durationMs = duration()
        }

        override fun onTracksChanged(tracks: Tracks) = readChapters(tracks)

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            // A loop, a zoom and a frame mode belong to the video they were made on.
            setAb(SeekLogic.AbRepeat())
            resetZoom()
            setFrameMode(false)
            thumbnails.setSource(thumbnailSource())
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) setFrameMode(false)
        }
    }

    init {
        binding.root.addView(preview, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.LEFT
        ))
        binding.controls.addView(frameBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = binding.toolbar.layoutParams.height })
        binding.root.addView(zoomPill, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
        ))
        binding.root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) applyZoom()
        }
        // Playback: after the speed (40), before the sleep timer (44); save frame after the video info (60).
        val menu = moreMenu
        chaptersItem = menu.add(R.id.groupVideoPlayback, View.generateViewId(), 41, R.string.video_seek_chapters).apply {
            setIcon(R.drawable.video_chapters_icon_24dp)
            isVisible = false
            setOnMenuItemClickListener { showChapters(); true }
        }
        menu.add(R.id.groupVideoPlayback, View.generateViewId(), 42, R.string.video_seek_frame_mode)
            .setIcon(R.drawable.video_seek_frame_ahead_icon_24dp)
            .setOnMenuItemClickListener { setFrameMode(true); true }
        abItem = menu.add(R.id.groupVideoPlayback, View.generateViewId(), 43, R.string.video_seek_ab_set_a).apply {
            setIcon(R.drawable.repeat_icon_24dp)
            setOnMenuItemClickListener { pressAb(); true }
        }
        // Never for a Tresor video (#101): the frame would land in the public Pictures folder.
        if (!VaultVideos.isVault(player.currentMediaItem?.mediaId)) {
            menu.add(R.id.groupVideoFile, View.generateViewId(), 61, R.string.video_seek_snapshot)
                .setIcon(R.drawable.camera_icon_white_24dp)
                .setOnMenuItemClickListener { saveFrame(); true }
        }

        binding.seekBar.addOnSliderTouchListener(object :
            com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {
                scrubbing = true
            }

            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                scrubbing = false
                hidePreview()
            }
        })
        binding.seekBar.addOnChangeListener { slider, value, fromUser ->
            if (fromUser && scrubbing) previewAt((duration() * value / slider.valueTo).toLong())
        }
        player.addListener(listener)
        thumbnails.setSource(thumbnailSource())
        readChapters(player.currentTracks)
    }

    fun release() {
        player.removeListener(listener)
        abMessage?.cancel()
        thumbnails.release()
        frameBack.remove()
        handler.removeCallbacksAndMessages(null)
    }

    // ---- Preview ----

    /** Shows the frame and chapter at [positionMs] above the seek bar, where the bar's thumb would be. */
    fun previewAt(positionMs: Long) {
        val duration = duration()
        if (duration <= 0) return
        val chapter = chapterTitleAt(positionMs)
        previewChapter.text = chapter
        previewChapter.isVisible = chapter != null
        val time = SeekLogic.thumbnailTimeMs(positionMs, duration)
        wantedThumbMs = time
        val cached = thumbnails.request(time) { readyMs, bitmap ->
            // Still the wish of a finger that has not lifted (hidePreview resets it).
            if (readyMs == wantedThumbMs) {
                showFrame(bitmap)
                place(lastFraction)
            }
        }
        if (cached != null) {
            showFrame(cached)
        } else if (!thumbnails.hasSource) {
            previewImage.isVisible = false
        }
        // Until the first frame arrives only a chapter name is worth a card.
        preview.isVisible = previewImage.isVisible && previewImage.drawable != null || chapter != null
        lastFraction = positionMs.toFloat() / duration
        place(lastFraction)
    }

    fun hidePreview() {
        wantedThumbMs = -1L
        preview.isVisible = false
        previewImage.setImageDrawable(null)
    }

    private fun showFrame(bitmap: Bitmap) {
        previewImage.setImageBitmap(bitmap)
        previewImage.isVisible = true
        // Tall films get a taller frame, up to square.
        val height = (thumbWidthPx.toLong() * bitmap.height / bitmap.width.coerceAtLeast(1)).toInt()
            .coerceAtMost(thumbWidthPx)
        if (previewImage.layoutParams.height != height) previewImage.updateLayoutParams { this.height = height }
        preview.isVisible = true
    }

    private fun place(fraction: Float) {
        val root = binding.root
        val bar = binding.seekBar
        val rootLocation = IntArray(2).also(root::getLocationInWindow)
        val barLocation = IntArray(2).also(bar::getLocationInWindow)
        val rtl = bar.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val f = fraction.coerceIn(0f, 1f).let { if (rtl) 1 - it else it }
        val centerX = barLocation[0] - rootLocation[0] + bar.trackSidePadding + bar.trackWidth * f
        preview.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val margin = 8 * density
        val maxX = (root.width - preview.measuredWidth - margin).coerceAtLeast(margin)
        preview.translationX = (centerX - preview.measuredWidth / 2f).coerceIn(margin, maxX)
        // Above the bar and the slider's own time bubble.
        val bottom = root.height - (barLocation[1] - rootLocation[1]) + (56 * density).toInt()
        if ((preview.layoutParams as FrameLayout.LayoutParams).bottomMargin != bottom) {
            preview.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = bottom }
        }
    }

    /** A local file or a content:// URI; network paths get no preview frames. */
    private fun thumbnailSource(): Any? {
        val mediaId = player.currentMediaItem?.mediaId ?: return null
        if (mediaId.startsWith("${ContentResolver.SCHEME_CONTENT}:")) return Uri.parse(mediaId)
        val path = runCatching { Paths.get(URI.create(mediaId)) }.getOrNull() ?: return null
        return if (path.isLinuxPath) path.toString() else null
    }

    // ---- Chapters ----

    private fun readChapters(tracks: Tracks) {
        val found = ArrayList<SeekLogic.Chapter>()
        for (group in tracks.groups) {
            for (index in 0 until group.length) {
                val metadata = group.getTrackFormat(index).metadata ?: continue
                for (i in 0 until metadata.length()) {
                    val entry = metadata.get(i) as? Chapter ?: continue
                    if (!entry.isHidden) found += SeekLogic.Chapter(entry.startTimeMs, entry.title?.value)
                }
            }
        }
        chapters = SeekLogic.normalizeChapters(found)
        marks.chapterStartsMs = LongArray(chapters.size) { chapters[it].startMs }
        chaptersItem.isVisible = chapters.isNotEmpty()
    }

    private fun chapterTitle(index: Int): String =
        chapters[index].title?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.video_seek_chapter_untitled, index + 1)

    private fun chapterTitleAt(positionMs: Long): String? =
        SeekLogic.chapterIndexAt(chapters, positionMs).takeIf { it >= 0 }?.let(::chapterTitle)

    /** The chapters as Klang rows: each with the chapter mark, the playing one with the play mark; the time on the end edge. */
    private fun showChapters() {
        if (chapters.isEmpty()) return
        val sheet = KlangSheet(context, R.drawable.video_chapters_icon_24dp, R.string.video_seek_chapters)
        val card = sheet.card(R.drawable.video_chapters_icon_24dp, null)
        val current = SeekLogic.chapterIndexAt(chapters, player.currentPosition)
        chapters.forEachIndexed { index, chapter ->
            val time = TextView(context, null, 0, R.style.Widget_Ordo_Klang_Value).apply { text = formatTime(chapter.startMs) }
            sheet.row(card, if (index == current) R.drawable.play_icon_24dp else R.drawable.video_chapters_icon_24dp, chapterTitle(index), null, time) {
                player.seekTo(chapter.startMs)
                sheet.dialog.dismiss()
            }.apply {
                isSelected = index == current
                if (index == current) {
                    ViewCompat.setStateDescription(this, context.getString(R.string.video_seek_chapter_current))
                }
            }
        }
        sheet.show()
    }

    // ---- Frame by frame ----

    private fun setFrameMode(on: Boolean) {
        if (frameMode == on) return
        frameMode = on
        frameBar.isVisible = on
        frameBack.isEnabled = on
        if (on) {
            player.pause()
            updateFrameLabel(player.currentPosition)
        }
    }

    /**
     * One frame on or back, paused. ExoPlayer's seeks are exact by default (SeekParameters.DEFAULT is EXACT): it
     * decodes from the key frame before the target and shows the first frame at or after it, so a step back costs
     * one key-frame interval of decoding (VIDEO-PLAYER-RECHERCHE §3).
     */
    private fun frameStep(forward: Boolean) {
        player.pause()
        val target = SeekLogic.frameStepTargetMs(player.currentPosition, fps(), forward, duration())
        player.seekTo(target)
        updateFrameLabel(target)
        if (!frameMode) showHint(frameLabel.text.toString())
    }

    private fun updateFrameLabel(positionMs: Long) {
        val frame = (positionMs * fps() / 1000.0).roundToInt() + 1
        frameLabel.text = context.getString(R.string.video_seek_frame_format, frame)
    }

    private fun fps(): Float = player.currentTracks.groups
        .firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
        ?.let { group -> (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) }
        ?.frameRate?.takeIf { it > 0f } ?: SeekLogic.FALLBACK_FPS

    /** A-B loop and zoom across a recreated screen (the picture's pipeline rebuild, #104). */
    fun saveState(outState: Bundle) {
        outState.putLongArray(STATE_AB, longArrayOf(ab.aMs, ab.bMs))
        outState.putFloatArray(STATE_ZOOM, floatArrayOf(zoom, panX, panY))
    }

    fun restoreState(state: Bundle?) {
        state?.getLongArray(STATE_AB)?.takeIf { it.size == 2 }?.let { setAb(SeekLogic.AbRepeat(it[0], it[1])) }
        state?.getFloatArray(STATE_ZOOM)?.takeIf { it.size == 3 }?.let {
            zoom = SeekLogic.clampZoom(it[0])
            panX = it[1]
            panY = it[2]
            // Before layout the pan would clamp to 0; the root's layout listener applies it then.
            if (binding.root.width > 0) applyZoom()
        }
    }

    // ---- A-B ----

    private fun pressAb() {
        val position = player.currentPosition
        val next = ab.next(position)
        when {
            next == ab -> showHint(context.getString(R.string.video_seek_ab_too_short))
            next.isLooping -> {
                setAb(next)
                player.seekTo(next.aMs)
                showHint(context.getString(
                    R.string.video_seek_ab_looping_format, formatTime(next.aMs), formatTime(next.bMs)
                ))
            }
            next.hasA -> {
                setAb(next)
                showHint(context.getString(R.string.video_seek_ab_a_format, formatTime(next.aMs)))
            }
            else -> {
                setAb(next)
                showHint(context.getString(R.string.video_seek_ab_cleared))
            }
        }
    }

    private fun setAb(value: SeekLogic.AbRepeat) {
        ab = value
        marks.ab = value
        abItem.setTitle(
            when {
                value.isLooping -> R.string.video_seek_ab_clear
                value.hasA -> R.string.video_seek_ab_set_b
                else -> R.string.video_seek_ab_set_a
            }
        )
        abMessage?.cancel()
        abMessage = null
        if (!value.isLooping) return
        // Delivered every time playback reaches B (also after seeking back before it), exact to the frame.
        abMessage = player.createMessage { _, _ -> if (ab == value) player.seekTo(value.aMs) }
            .setLooper(Looper.getMainLooper())
            .setPosition(player.currentMediaItemIndex, value.bMs)
            .setDeleteAfterDelivery(false)
            .send()
    }

    // ---- Save frame ----

    /**
     * What the screen shows, copied from the SurfaceView (PixelCopy, API 24) at the video's size, as JPEG into
     * Pictures/Ordo. HDR films come out as SDR (PixelCopy's documented limit).
     */
    private fun saveFrame() {
        if (VaultVideos.isVault(player.currentMediaItem?.mediaId)) return
        val surface = binding.playerView.videoSurfaceView as? SurfaceView
        // With Ordo's picture enhancement on, the effects pipeline reports the size as unknown (0 × 0, Tab S9):
        // then the decoder's input format gives it.
        val size = player.videoSize.takeIf { it.width > 0 && it.height > 0 }
            ?: player.videoFormat?.let { VideoSize(it.width, it.height, it.pixelWidthHeightRatio) }
            ?: player.videoSize
        if (surface == null || !surface.holder.surface.isValid || size.width <= 0 || size.height <= 0) {
            android.util.Log.w(FRAME_TAG, "no picture to copy: surface=${surface != null} size=${size.width}x${size.height}")
            context.showToast(R.string.video_seek_snapshot_failed)
            return
        }
        var width = (size.width * size.pixelWidthHeightRatio).roundToInt()
        var height = size.height
        val longest = maxOf(width, height)
        if (longest > MAX_SNAPSHOT_PX) {
            width = width * MAX_SNAPSHOT_PX / longest
            height = height * MAX_SNAPSHOT_PX / longest
        }
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val name = snapshotName()
        PixelCopy.request(surface, bitmap, { result ->
            if (result != PixelCopy.SUCCESS) {
                android.util.Log.w(FRAME_TAG, "PixelCopy failed: $result")
                bitmap.recycle()
                context.showToast(R.string.video_seek_snapshot_failed)
                return@request
            }
            activity.lifecycleScope.launch {
                val uri = withContext(Dispatchers.IO) { runCatching { writeJpeg(bitmap, name) }
                    .onFailure { android.util.Log.w(FRAME_TAG, "writing $name failed", it) }.getOrNull() }
                bitmap.recycle()
                if (uri == null) {
                    context.showToast(R.string.video_seek_snapshot_failed)
                } else {
                    Snackbar.make(binding.root, R.string.video_seek_snapshot_saved, Snackbar.LENGTH_LONG)
                        .setAction(R.string.video_seek_snapshot_open) {
                            context.startActivitySafe(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                        .show()
                }
            }
        }, handler)
    }

    private fun snapshotName(): String {
        val title = player.currentMediaItem?.mediaMetadata?.displayTitle?.toString()
            ?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Ordo"
        val safe = title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(80)
        val ms = player.currentPosition.coerceAtLeast(0L)
        val stamp = String.format(
            java.util.Locale.ROOT, "%02d-%02d-%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60, ms % 1_000
        )
        return "${safe}_$stamp.jpg"
    }

    // IO thread. Below Android 10 Ordo, a file manager, already holds storage access.
    private suspend fun writeJpeg(bitmap: Bitmap, name: String): Uri? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Ordo")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            val written = runCatching {
                resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            }.getOrDefault(false)
            if (!written) {
                resolver.delete(uri, null, null)
                return null
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Ordo")
        dir.mkdirs()
        val file = File(dir, name)
        val written = file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (!written) {
            file.delete()
            return null
        }
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            MediaScanner.scanPaths(listOf(file.path)) { _, uri ->
                if (continuation.isActive) continuation.resumeWith(Result.success(uri))
            }
        }
    }

    // ---- Zoom ----

    /**
     * Two fingers on the picture pinch and pan it; the screen's one-finger gestures get a cancel first, so a pinch
     * never also seeks, changes volume or toggles the controls. A quick two-finger tap resets. Returns true while the
     * touch belongs to the pinch.
     */
    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (!pinching && event.pointerCount == 2 && !isLocked()) {
                pinching = true
                pinchMoved = false
                pinchDownTime = event.eventTime
                lastFocusX = focusX(event)
                lastFocusY = focusY(event)
                startSpan = span(event)
                startFocusX = lastFocusX
                startFocusY = lastFocusY
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                binding.root.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
        }
        if (!pinching) return false
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (event.pointerCount >= 2) {
                val fx = focusX(event)
                val fy = focusY(event)
                if (abs(span(event) - startSpan) > touchSlop) pinchMoved = true
                if (abs(fx - startFocusX) > touchSlop || abs(fy - startFocusY) > touchSlop) pinchMoved = true
                if (zoom > 1f) {
                    panX += fx - lastFocusX
                    panY += fy - lastFocusY
                    applyZoom()
                }
                lastFocusX = fx
                lastFocusY = fy
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // The remaining finger's focus is the new reference, so the picture does not jump.
                lastFocusX = focusX(event, event.actionIndex)
                lastFocusY = focusY(event, event.actionIndex)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pinching = false
                if (event.actionMasked == MotionEvent.ACTION_UP && !pinchMoved &&
                    event.eventTime - pinchDownTime < TWO_FINGER_TAP_MS) {
                    resetZoom()
                }
            }
        }
        return true
    }

    private fun setZoom(value: Float) {
        val clamped = SeekLogic.clampZoom(value)
        if (abs(clamped - zoom) > 0.01f) pinchMoved = true
        zoom = clamped
        applyZoom()
    }

    fun resetZoom() {
        zoom = 1f
        panX = 0f
        panY = 0f
        applyZoom()
    }

    private fun applyZoom() {
        val root = binding.root
        val view = binding.playerView
        val zoomed = zoom > 1.001f
        val width = if (zoomed) (root.width * zoom).roundToInt() else ViewGroup.LayoutParams.MATCH_PARENT
        val height = if (zoomed) (root.height * zoom).roundToInt() else ViewGroup.LayoutParams.MATCH_PARENT
        val params = view.layoutParams as FrameLayout.LayoutParams
        if (params.width != width || params.height != height || params.gravity != Gravity.CENTER) {
            view.updateLayoutParams<FrameLayout.LayoutParams> {
                this.width = width
                this.height = height
                gravity = Gravity.CENTER
            }
        }
        panX = SeekLogic.clampPan(panX, zoom, root.width)
        panY = SeekLogic.clampPan(panY, zoom, root.height)
        view.translationX = panX
        view.translationY = panY
        zoomPill.isVisible = zoomed
        if (zoomed) {
            val label = context.getString(R.string.video_seek_zoom_format, java.text.NumberFormat.getInstance()
                .apply { maximumFractionDigits = 1; minimumFractionDigits = 1 }.format(zoom.toDouble()))
            zoomPill.text = label
            zoomPill.contentDescription = context.getString(R.string.video_seek_zoom_reset_format, label)
            val insets = ViewCompat.getRootWindowInsets(root)
                ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            zoomPill.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = binding.resumePill.marginTop
                marginEnd = (insets?.right ?: 0) + (16 * density).toInt()
            }
        }
    }

    private fun focusX(event: MotionEvent, skip: Int = -1): Float = average(event, skip) { event.getX(it) }

    private fun focusY(event: MotionEvent, skip: Int = -1): Float = average(event, skip) { event.getY(it) }

    private inline fun average(event: MotionEvent, skip: Int, value: (Int) -> Float): Float {
        var sum = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == skip) continue
            sum += value(i)
            count++
        }
        return if (count == 0) 0f else sum / count
    }

    private fun span(event: MotionEvent): Float =
        if (event.pointerCount < 2) 0f else kotlin.math.hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    // ---- Keys ----

    /** Keyboard, Chromebook and TV-remote keys ([SeekKeys]); true when handled. */
    fun onKey(event: KeyEvent): Boolean {
        if (isLocked()) return false
        val focus = activity.currentFocus
        val focusOnControl = focus != null && focus !== binding.playerView && focus !== binding.root
        val modifiers = event.isCtrlPressed || event.isAltPressed || event.isMetaPressed
        val action = SeekKeys.action(event.keyCode, modifiers, focusOnControl) ?: return false
        // The key's release belongs to the same action; repeats of a held key seek on.
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val repeat = event.repeatCount > 0
        when (action) {
            SeekKeys.Action.PLAY_PAUSE -> if (!repeat) playPause()
            SeekKeys.Action.SEEK_BACK -> seekKeyed(-jumpMs())
            SeekKeys.Action.SEEK_AHEAD -> seekKeyed(jumpMs())
            SeekKeys.Action.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
            SeekKeys.Action.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
            SeekKeys.Action.TOGGLE_CONTROLS -> if (!repeat) toggleControls()
            SeekKeys.Action.MUTE -> if (!repeat) adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE)
            SeekKeys.Action.FRAME_BACK -> frameStep(false)
            SeekKeys.Action.FRAME_AHEAD -> frameStep(true)
        }
        return true
    }

    private fun seekKeyed(deltaMs: Long) {
        seekBy(deltaMs)
        showHint(formatTime(player.currentPosition))
    }

    private fun adjustVolume(direction: Int) {
        audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    // ---- Helpers ----

    private fun duration(): Long = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L

    private fun formatTime(ms: Long): String = Util.getStringForTime(timeBuilder, timeFormatter, ms.coerceAtLeast(0L))

    private fun controlButton(icon: Int, description: Int, onClick: () -> Unit): ImageButton =
        ImageButton(context, null, 0, R.style.Widget_Ordo_AudioPlayer_SecondaryControl).apply {
            setImageResource(icon)
            contentDescription = context.getString(description)
            setOnClickListener { onClick() }
            val size = context.resources.getDimensionPixelSize(R.dimen.audio_player_secondary_control_size)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }

    private fun TextView.hyphenationFrequencyCompat() {
        // No mid-word breaks (house rule).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
    }

    companion object {
        private const val STATE_AB = "seekPro.ab"
        private const val STATE_ZOOM = "seekPro.zoom"
        private const val MAX_SNAPSHOT_PX = 3840
        private const val JPEG_QUALITY = 95
        private const val FRAME_TAG = "OrdoVideoFrame"
        private const val TWO_FINGER_TAP_MS = 300L
    }
}
