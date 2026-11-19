/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.getSystemService
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.net.URI
import java.text.NumberFormat
import java.util.Formatter
import java.util.UUID
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.databinding.VideoPlayerActivityBinding
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.fileproperties.FilePropertiesDialogFragment
import me.zhanghai.android.files.player.AudioPlaybackService
import me.zhanghai.android.files.player.AudioQueueRequests
import me.zhanghai.android.files.player.PathDataSource
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.startActivitySafe

/**
 * Ordo's video player. The activity owns its ExoPlayer and a MediaSession (media keys, `dumpsys media_session`); the
 * video stops with the screen. Exported for ACTION_VIEW of every video type, with the same rule as the music player:
 * Ordo's own screens pass the folder's videos through [AudioQueueRequests] and only a token in the intent; from any
 * other caller only a `content://` URI with a read grant is played, and path extras are ignored.
 */
@OptIn(UnstableApi::class)
class VideoPlayerActivity : AppActivity() {
    private lateinit var binding: VideoPlayerActivityBinding
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private lateinit var controls: VideoControls
    private lateinit var resumeStore: VideoResumeStore
    private val handler = Handler(Looper.getMainLooper())
    private val timeBuilder = StringBuilder()
    private val timeFormatter = Formatter(timeBuilder)
    private var userSeeking = false
    private var showRemaining = false
    private var progressDescription: String? = null
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var userOrientation = false
    // Resume keys of Ordo paths by media ID; content:// items of other apps never get one.
    private val resumeKeys = HashMap<String, String>()
    private var trackedMediaId: String? = null
    private var trackedPosition = 0L
    private var trackedDuration = 0L
    private var ticks = 0

    private val isPhone: Boolean
        get() = resources.configuration.smallestScreenWidthDp < 600

    private val touchExploration: Boolean
        get() = getSystemService<AccessibilityManager>()?.isTouchExplorationEnabled == true

    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgress()
            if (++ticks % SAVE_EVERY_TICKS == 0) saveResume()
            if (player?.isPlaying == true) handler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
        }
    }

    private val hideHint = Runnable { binding.hint.isVisible = false }
    private val hideResumePill = Runnable { binding.resumePill.isVisible = false }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_AVAILABLE_COMMANDS_CHANGED
                )) {
                updateUi(player)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The tracked values still belong to the video that just ended or was left; one that ran out is done.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) trackedPosition = trackedDuration
            saveResume()
            startTracking(mediaItem)
            binding.errorPanel.isVisible = false
            binding.resumePill.isVisible = false
            mediaItem?.let { offerResume(it, showPill = true) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // A retry after an error got through.
            if (playbackState == Player.STATE_READY) binding.errorPanel.isVisible = false
            if (playbackState == Player.STATE_ENDED) {
                // Watched to the end: the entry is forgotten (more than 95 %).
                trackedPosition = trackedDuration
                saveResume()
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = followVideoOrientation(videoSize)

        override fun onPlayerError(error: PlaybackException) {
            binding.errorText.setText(errorMessage(error))
            binding.errorPanel.isVisible = true
            binding.resumePill.isVisible = false
            controls.setShown(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = VideoPlayerActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        resumeStore = VideoResumeStore(this)
        val queue = resolveLaunchQueue(savedInstanceState)
        if (queue == null) {
            if (hasLaunchContent()) {
                Toast.makeText(this, R.string.video_player_external_refused, Toast.LENGTH_LONG).show()
            }
            finish()
            return
        }
        resizeMode = savedInstanceState?.getInt(STATE_RESIZE_MODE, resizeMode) ?: resizeMode
        setUpWindow()
        setUpControls()
        binding.playerView.resizeMode = resizeMode
        userOrientation = savedInstanceState?.getBoolean(STATE_USER_ORIENTATION) == true
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(PathDataSource.Factory(this)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            // As the music player: keeps Wi-Fi up for SMB/SFTP/WebDAV/FTP films.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        this.player = player
        // Its own ID: the music service's session lives in the same process.
        // Unique per screen (two can overlap while one closes). Controllers (media keys, the system) may play and seek
        // but never hand the player items of their own: that would bypass the launch rule above.
        session = MediaSession.Builder(this, player)
            .setId("$SESSION_ID.${UUID.randomUUID()}")
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>
                ): ListenableFuture<MutableList<MediaItem>> =
                    Futures.immediateFailedFuture(UnsupportedOperationException())
            })
            .build()
        binding.playerView.player = player
        savedInstanceState?.getFloat(STATE_SPEED)?.takeIf { it > 0f }?.let {
            player.setPlaybackSpeed(it)
            ViewCompat.setStateDescription(binding.speed, formatSpeed(it))
        }
        player.setMediaItems(queue.items, queue.startIndex, queue.positionMs)
        player.playWhenReady = savedInstanceState?.getBoolean(STATE_PLAY_WHEN_READY) ?: true
        player.prepare()
        // After setMediaItems, so the first item is not announced twice; a recreated screen offers no pill.
        player.addListener(playerListener)
        startTracking(player.currentMediaItem)
        player.currentMediaItem?.let { offerResume(it, showPill = savedInstanceState == null) }
        updateUi(player)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val player = player ?: return
        outState.putString(STATE_MEDIA_ID, player.currentMediaItem?.mediaId)
        outState.putInt(STATE_INDEX, player.currentMediaItemIndex)
        outState.putLong(STATE_POSITION, player.currentPosition)
        outState.putBoolean(STATE_PLAY_WHEN_READY, player.playWhenReady)
        outState.putFloat(STATE_SPEED, player.playbackParameters.speed)
        outState.putInt(STATE_RESIZE_MODE, resizeMode)
        outState.putBoolean(STATE_USER_ORIENTATION, userOrientation)
    }

    override fun onStop() {
        // Phase 1 has no background playback: the film pauses with the screen, its place is kept.
        player?.let {
            trackedPosition = it.currentPosition
            saveResume()
            it.pause()
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session?.release()
        session = null
        player?.release()
        player = null
        if (isFinishing) AudioQueueRequests.release(intent.getStringExtra(EXTRA_QUEUE_TOKEN))
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::controls.isInitialized) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> controls.holdOpen()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> controls.scheduleHide()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private class LaunchQueue(val items: List<MediaItem>, val startIndex: Int, val positionMs: Long)

    private fun hasLaunchContent(): Boolean =
        intent.getStringExtra(EXTRA_QUEUE_TOKEN) != null || intent.data != null

    /**
     * Ordo's own queue comes through its token (kept until the player closes, so a recreated screen finds it again);
     * after the process was gone only the video saved in the instance state is left. Anything else is an external
     * caller: only a `content://` URI that carries a read grant counts.
     */
    private fun resolveLaunchQueue(savedInstanceState: Bundle?): LaunchQueue? =
        try {
            val savedIndex = savedInstanceState?.getInt(STATE_INDEX) ?: 0
            val savedPosition = savedInstanceState?.getLong(STATE_POSITION) ?: 0L
            val request = AudioQueueRequests.from(intent.getStringExtra(EXTRA_QUEUE_TOKEN))
            val savedMediaId = savedInstanceState?.getString(STATE_MEDIA_ID)
            when {
                request != null && request.paths.isNotEmpty() -> {
                    val items = request.paths.map { AudioPlaybackService.mediaItem(it.toUri().toString()) }
                    if (savedInstanceState != null) {
                        LaunchQueue(items, savedIndex.coerceIn(items.indices), savedPosition)
                    } else {
                        LaunchQueue(items, request.startIndex.coerceIn(items.indices), 0L)
                    }
                }
                // Written by this activity itself, so it is trusted like the token was.
                savedMediaId != null ->
                    LaunchQueue(listOf(AudioPlaybackService.mediaItem(savedMediaId)), 0, savedPosition)
                else -> intent.data?.takeIf(::isAllowedExternalUri)
                    ?.let { LaunchQueue(listOf(AudioPlaybackService.mediaItem(it.toString())), 0, 0L) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

    // The same rule as AudioPlayerActivity: a caller never gets to point Ordo at a file through Ordo's own access.
    private fun isAllowedExternalUri(uri: Uri): Boolean {
        val authority = uri.authority
        return uri.scheme == ContentResolver.SCHEME_CONTENT
            && !authority.isNullOrEmpty()
            // "userId@authority" would slip past the own-authority check.
            && '@' !in authority
            && !isOwnAuthority(authority)
            && intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
    }

    private fun isOwnAuthority(authority: String): Boolean =
        authority.startsWith(BuildConfig.APPLICATION_ID)
            || authority.startsWith(packageName)
            || authority == getString(R.string.file_provider_authority)
            || authority == getString(R.string.app_provider_authority)

    /**
     * Edge to edge, system bars hidden with the controls and back by a swipe (the back gesture stays the system's).
     * Controls sit inside the bars and 8 dp above the gesture strip; no gesture exclusion rects anywhere.
     */
    private fun setUpWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val gap = resources.getDimensionPixelSize(R.dimen.phi_8)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            // Ignoring visibility: the controls must not jump when the bars come and go.
            val bars = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.controls.setPadding(bars.left, bars.top, bars.right, bars.bottom + gap)
            binding.resumePill.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = bars.top + binding.toolbar.layoutParams.height + gap
            }
            binding.unlock.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = bars.bottom + gap * 3 }
            val gestures = Insets.max(
                insets.getInsets(WindowInsetsCompat.Type.systemGestures()),
                insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
            )
            if (::controls.isInitialized) {
                controls.gestureInsets = intArrayOf(
                    gestures.left, maxOf(gestures.top, bars.top), gestures.right, gestures.bottom
                )
            }
            insets
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpControls() {
        controls = VideoControls(
            binding.controls, binding.unlock,
            stayVisible = {
                touchExploration || player.let { it == null || Util.shouldShowPlayButton(it) } ||
                    binding.errorPanel.isVisible
            },
            onShownChanged = { shown ->
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                if (shown) {
                    insetsController.show(WindowInsetsCompat.Type.systemBars())
                } else {
                    insetsController.hide(WindowInsetsCompat.Type.systemBars())
                }
            },
            onSeekTap = { forward, seconds ->
                seekBy(if (forward) SEEK_STEP_MS else -SEEK_STEP_MS)
                showHint(
                    getString(
                        if (forward) {
                            R.string.video_player_seek_forward_format
                        } else {
                            R.string.video_player_seek_back_format
                        },
                        seconds
                    ),
                    if (forward) Gravity.CENTER_VERTICAL or Gravity.END else Gravity.CENTER_VERTICAL or Gravity.START
                )
            },
            onPlayPauseTap = ::playPause,
            onLockedChanged = { locked ->
                if (locked) {
                    binding.resumePill.isVisible = false
                    binding.hint.isVisible = false
                }
                ViewCompat.setStateDescription(
                    binding.playerView, if (locked) getString(R.string.video_player_locked) else null
                )
            }
        )
        binding.root.setOnTouchListener(controls::onTouch)
        ViewCompat.replaceAccessibilityAction(
            binding.unlock, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            getString(R.string.video_player_unlock), null
        )
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.actionOpenWith -> openWithOtherApp()
                R.id.actionFileInfo -> showFileInfo()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        binding.playPause.setOnClickListener { playPause() }
        binding.rewind.setOnClickListener { seekBy(-SEEK_STEP_MS) }
        binding.forward.setOnClickListener { seekBy(SEEK_STEP_MS) }
        binding.previous.setOnClickListener { player?.let { it.seekToPreviousMediaItem(); prepareAfterError(it) } }
        binding.next.setOnClickListener { player?.let { it.seekToNextMediaItem(); prepareAfterError(it) } }
        binding.speed.setOnClickListener { showSpeedSheet() }
        binding.resize.setOnClickListener { cycleResizeMode() }
        binding.lock.setOnClickListener { controls.setLocked(true) }
        binding.rotate.isVisible = isPhone
        binding.rotate.setOnClickListener {
            userOrientation = true
            requestedOrientation =
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
        }
        binding.elapsed.setOnClickListener {
            showRemaining = !showRemaining
            updateProgress()
        }
        binding.errorOpenWith.setOnClickListener { openWithOtherApp() }
        binding.seekBar.setLabelFormatter { formatTime((currentDuration() * it / SEEK_MAX).toLong()) }
        binding.seekBar.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val position = (currentDuration() * value / SEEK_MAX).toLong()
            binding.elapsed.text = formatTime(position)
            // Accessibility actions and keys step the bar without a touch; they seek straight away.
            if (!userSeeking && currentDuration() > 0) player?.seekTo(position)
        }
        binding.seekBar.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                val duration = currentDuration()
                if (duration > 0) player?.seekTo((duration * slider.value / SEEK_MAX).toLong())
                userSeeking = false
                updateProgress()
            }
        })
        // TalkBack: the picture offers what the double taps do, and a way to bring the controls back.
        ViewCompat.addAccessibilityAction(binding.playerView, getString(R.string.video_player_rewind)) { _, _ ->
            seekBy(-SEEK_STEP_MS); true
        }
        ViewCompat.addAccessibilityAction(binding.playerView, getString(R.string.video_player_forward)) { _, _ ->
            seekBy(SEEK_STEP_MS); true
        }
        ViewCompat.addAccessibilityAction(binding.playerView, getString(R.string.video_player_show_controls)) { _, _ ->
            controls.setShown(true); true
        }
        ViewCompat.setStateDescription(binding.speed, formatSpeed(1f))
        ViewCompat.setStateDescription(binding.resize, getString(resizeModeName(resizeMode)))
    }

    private fun playPause() {
        val player = player ?: return
        prepareAfterError(player)
        Util.handlePlayPauseButtonAction(player)
    }

    /** After an error the player is idle; the next button or play gives the (next) video a new try. */
    private fun prepareAfterError(player: Player) {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    private fun seekBy(deltaMs: Long) {
        val player = player ?: return
        val duration = currentDuration()
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        player.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
        updateProgress()
    }

    private fun updateUi(player: Player) {
        val playing = !Util.shouldShowPlayButton(player)
        binding.playPause.setImageResource(if (playing) R.drawable.pause_icon_24dp else R.drawable.play_icon_24dp)
        binding.playPause.contentDescription =
            getString(if (playing) R.string.video_player_pause else R.string.video_player_play)
        binding.previous.isEnabled = player.hasPreviousMediaItem()
        binding.next.isEnabled = player.hasNextMediaItem()
        binding.previous.alpha = if (binding.previous.isEnabled) 1f else DISABLED_CONTROL_ALPHA
        binding.next.alpha = if (binding.next.isEnabled) 1f else DISABLED_CONTROL_ALPHA
        binding.seekBar.isEnabled = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            && player.duration != C.TIME_UNSET
        val title = player.currentMediaItem?.mediaMetadata?.displayTitle
        binding.toolbar.title = title
        binding.playerView.contentDescription = title
        binding.toolbar.menu.findItem(R.id.actionFileInfo)?.isVisible = currentPath() != null
        binding.playerView.keepScreenOn = player.isPlaying
        handler.removeCallbacks(progressUpdater)
        updateProgress()
        if (player.isPlaying) handler.postDelayed(progressUpdater, PROGRESS_UPDATE_INTERVAL_MS)
        // Paused or ended, the controls stay; a short rebuffer while playing does not bring them back.
        if (playing) controls.scheduleHide() else controls.setShown(true)
    }

    private fun updateProgress() {
        if (userSeeking) return
        val player = player ?: return
        val duration = currentDuration()
        val position = player.currentPosition.coerceIn(0L, duration.coerceAtLeast(0L))
        if (player.currentMediaItem?.mediaId == trackedMediaId) {
            trackedPosition = position
            trackedDuration = duration
        }
        binding.seekBar.value = if (duration > 0) (position * SEEK_MAX / duration).toFloat() else 0f
        binding.elapsed.text = if (showRemaining) "−${formatTime(duration - position)}" else formatTime(position)
        binding.elapsed.contentDescription = getString(
            if (showRemaining) R.string.video_player_show_elapsed else R.string.video_player_show_remaining
        )
        binding.duration.text = formatTime(duration)
        val description = getString(R.string.video_player_progress_format, formatTime(position), formatTime(duration))
        if (description != progressDescription) {
            progressDescription = description
            ViewCompat.setStateDescription(binding.seekBar, description)
        }
    }

    private fun currentDuration(): Long = player?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L

    private fun formatTime(milliseconds: Long): String =
        Util.getStringForTime(timeBuilder, timeFormatter, milliseconds.coerceAtLeast(0L))

    /** The current video as an Ordo path; null for a content:// video of another app. */
    private fun currentPath(): Path? = player?.currentMediaItem?.mediaId?.let(::ordoPath)

    private fun ordoPath(mediaId: String): Path? =
        mediaId.takeIf { !it.startsWith("${ContentResolver.SCHEME_CONTENT}:") }
            ?.let { runCatching { Paths.get(URI.create(it)) }.getOrNull() }

    private fun startTracking(item: MediaItem?) {
        trackedMediaId = item?.mediaId
        trackedPosition = 0L
        trackedDuration = 0L
    }

    private fun saveResume() {
        // While "Resume at" is still offered nothing is decided: closing then must not forget the place.
        if (binding.resumePill.isVisible) return
        val key = trackedMediaId?.let { resumeKeys[it] } ?: return
        resumeStore.write(key, trackedPosition, trackedDuration)
    }

    /** Finds the video's key in the background; a saved place shows as a pill for 5 s, a tap goes there. */
    private fun offerResume(item: MediaItem, showPill: Boolean) {
        val mediaId = item.mediaId
        val path = ordoPath(mediaId) ?: return
        lifecycleScope.launch {
            val key = withContext(Dispatchers.IO) {
                runCatching {
                    val attributes = path.readAttributes(BasicFileAttributes::class.java)
                    VideoResumeStore.key(mediaId, attributes.size(), attributes.lastModifiedTime().toMillis())
                }.getOrNull()
            } ?: return@launch
            resumeKeys[mediaId] = key
            if (!showPill) return@launch
            val position = resumeStore.read(key) ?: return@launch
            val player = player ?: return@launch
            if (player.currentMediaItem?.mediaId != mediaId || player.currentPosition >= position) return@launch
            binding.resumePill.text = getString(R.string.video_player_resume_format, formatTime(position))
            binding.resumePill.setOnClickListener {
                binding.resumePill.isVisible = false
                if (this@VideoPlayerActivity.player?.currentMediaItem?.mediaId == mediaId) {
                    this@VideoPlayerActivity.player?.seekTo(position)
                }
            }
            binding.resumePill.isVisible = true
            handler.removeCallbacks(hideResumePill)
            handler.postDelayed(hideResumePill, RESUME_PILL_MS)
        }
    }

    private fun followVideoOrientation(videoSize: VideoSize) {
        if (!isPhone || userOrientation || videoSize.width == 0 || videoSize.height == 0) return
        // Like Next Player's "video orientation": wide films turn the phone, tall ones keep it upright.
        requestedOrientation = if (videoSize.width * videoSize.pixelWidthHeightRatio > videoSize.height) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    private fun cycleResizeMode() {
        resizeMode = when (resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        binding.playerView.resizeMode = resizeMode
        val name = getString(resizeModeName(resizeMode))
        ViewCompat.setStateDescription(binding.resize, name)
        showHint(name, Gravity.CENTER)
    }

    private fun resizeModeName(mode: Int): Int = when (mode) {
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> R.string.video_player_resize_fill
        AspectRatioFrameLayout.RESIZE_MODE_FILL -> R.string.video_player_resize_stretch
        else -> R.string.video_player_resize_fit
    }

    private fun showHint(text: String, gravity: Int) {
        val hint = binding.hint
        hint.text = text
        hint.updateLayoutParams<FrameLayout.LayoutParams> { this.gravity = gravity }
        hint.animate().cancel()
        hint.alpha = 1f
        hint.isVisible = true
        handler.removeCallbacks(hideHint)
        if (Motion.any) {
            hint.animate().alpha(0f).setStartDelay(HINT_MS).setDuration(FADE_MS).withEndAction(hideHint).start()
        } else {
            handler.postDelayed(hideHint, HINT_MS)
        }
    }

    private fun formatSpeed(speed: Float): String =
        getString(R.string.video_player_speed_format, NumberFormat.getInstance().format(speed.toDouble()))

    /** Speeds as chips, never a slider; the pitch stays (Media3's default for setPlaybackSpeed). */
    private fun showSpeedSheet() {
        val player = player ?: return
        val dialog = BottomSheetDialog(this)
        val padding = resources.getDimensionPixelSize(R.dimen.phi_21)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            setText(R.string.video_player_speed_title)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Headline6)
            ViewCompat.setAccessibilityHeading(this, true)
        })
        val group = ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        for (speed in SPEEDS) {
            group.addView(Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId()
                text = formatSpeed(speed)
                isCheckable = true
                isChecked = speed == player.playbackParameters.speed
                setOnClickListener {
                    player.setPlaybackSpeed(speed)
                    ViewCompat.setStateDescription(binding.speed, formatSpeed(speed))
                    dialog.dismiss()
                }
            })
        }
        content.addView(group, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = padding / 2 })
        dialog.setContentView(content)
        dialog.show()
    }

    private fun errorMessage(error: PlaybackException): Int = when (error.errorCode) {
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> R.string.video_player_error_damaged
        // Media3's code ranges: 2xxx input/output, 3xxx parsing, 4xxx decoding.
        in 2000..2999 -> R.string.video_player_error_read
        in 3000..4999 -> R.string.video_player_error_format
        else -> R.string.video_player_error
    }

    /** "Open with another app": every app but this player, with read access to the file. */
    private fun openWithOtherApp() {
        player?.pause()
        val path = currentPath()
        if (path == null) {
            val uri = player?.currentMediaItem?.mediaId?.let(Uri::parse) ?: return
            val type = intent.type ?: contentResolver.getType(uri) ?: "video/*"
            startChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            return
        }
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { path.loadFileItem() }.getOrNull() } ?: return@launch
            startChooser(path.fileProviderUri.createViewIntent(file.mimeType))
        }
    }

    private fun startChooser(view: Intent) {
        startActivitySafe(Intent.createChooser(view, null).putExtra(
            Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, VideoPlayerActivity::class.java))
        ))
    }

    private fun showFileInfo() {
        val path = currentPath() ?: return
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { path.loadFileItem() }.getOrNull() } ?: return@launch
            if (isFinishing || supportFragmentManager.isStateSaved) return@launch
            FilePropertiesDialogFragment().putArgs(FilePropertiesDialogFragment.Args(file))
                .show(supportFragmentManager, null)
        }
    }

    companion object {
        private const val EXTRA_QUEUE_TOKEN = "me.zhanghai.android.files.viewer.video.extra.QUEUE_TOKEN"
        private const val STATE_MEDIA_ID = "mediaId"
        private const val STATE_INDEX = "index"
        private const val STATE_POSITION = "position"
        private const val STATE_PLAY_WHEN_READY = "playWhenReady"
        private const val STATE_SPEED = "speed"
        private const val STATE_RESIZE_MODE = "resizeMode"
        private const val STATE_USER_ORIENTATION = "userOrientation"
        private const val SESSION_ID = "video"
        private const val SEEK_MAX = 1_000
        private const val SEEK_STEP_MS = VideoControls.SEEK_STEP_S * 1_000L
        private const val PROGRESS_UPDATE_INTERVAL_MS = 1_000L
        // The place is written every 5 s while the film plays (and when it stops or changes).
        private const val SAVE_EVERY_TICKS = 5
        private const val RESUME_PILL_MS = 5_000L
        private const val HINT_MS = 700L
        private const val FADE_MS = 200L
        private const val DISABLED_CONTROL_ALPHA = 0.38f
        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

        /** The folder's videos stay in this process; the intent only carries the token to take them over. */
        fun putExtras(intent: Intent, paths: List<Path>, position: Int) {
            intent.putExtra(
                EXTRA_QUEUE_TOKEN, AudioQueueRequests.register(AudioQueueRequests.Request(paths, position))
            )
        }
    }
}
