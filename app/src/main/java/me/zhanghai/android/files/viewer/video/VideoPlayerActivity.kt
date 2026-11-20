/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

import me.zhanghai.android.files.theme.custom.AppAccentActivity
import me.zhanghai.android.files.settings.SettingLiveData
import me.zhanghai.android.files.settings.Settings
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.core.content.getSystemService
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.marginTop
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
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
import me.zhanghai.android.files.player.AudioPlaybackService
import me.zhanghai.android.files.player.AudioQueueRequests
import me.zhanghai.android.files.viewer.audio.CastChooserSheet
import me.zhanghai.android.files.viewer.video.cast.VideoCastSession
import me.zhanghai.android.files.viewer.video.network.VideoNetworkPlayback
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.startActivitySafe

/**
 * Ordo's video player. The activity owns its ExoPlayer and a MediaSession (media keys, `dumpsys media_session`); the
 * video stops with the screen. Exported for ACTION_VIEW of every video type, with the same rule as the music player:
 * Ordo's own screens pass the folder's videos through [AudioQueueRequests] and only a token in the intent; from any
 * other caller only a `content://` URI with a read grant is played, and path extras are ignored.
 */
@OptIn(UnstableApi::class)
class VideoPlayerActivity : AppActivity(), AppAccentActivity, CastChooserSheet.Host {
    override val appAccent: SettingLiveData<String>
        get() = Settings.VIDEO_ACCENT

    // Films from SMB/SFTP/WebDAV/FTP (buffer, reconnect) and on a TV; see docs/VIDEO-PLAYER.md.
    private var network: VideoNetworkPlayback? = null
    private var castSession: VideoCastSession? = null
    override val castController: VideoCastSession
        get() = castSession
            ?: VideoCastSession(binding.root, binding.rewind, binding.forward, { jumpMs }, castHost).also { castSession = it }

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
    // Opened from Ordo Video's "Continue watching": the first video goes straight to its place, no pill.
    private var resumeDirectly = false

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
    // The offer stays while the film is paused (nothing decided yet); it goes 5 s into playback.
    private val hideResumePill: Runnable = object : Runnable {
        override fun run() {
            if (player?.isPlaying == false) {
                handler.postDelayed(this, RESUME_PILL_MS)
            } else {
                binding.resumePill.isVisible = false
            }
        }
    }
    private val hideNotice = Runnable { binding.notice.isVisible = false }
    // The speed before "hold for 2×" while a finger holds; 0 otherwise.
    private var speedBeforeHold = 0f
    private var audioNoticeFor: String? = null
    private var repeatMode = VideoRepeatMode.STOP
    private val videoSettings by lazy { getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE) }
    private val jumpSeconds: Int
        get() = VideoPrefs.jumpSeconds(Settings.VIDEO_JUMP.valueCompat)
    private val jumpMs: Long
        get() = jumpSeconds * 1_000L

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
            resumeDirectly = false
            showError(null)
            showEnd(false)
            binding.notice.isVisible = false
            binding.resumePill.isVisible = false
            mediaItem?.let { offerResume(it, showPill = true) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // A retry after an error got through.
            if (playbackState == Player.STATE_READY) showError(null)
            if (playbackState == Player.STATE_ENDED) {
                // Watched to the end: the entry is forgotten (more than 95 %).
                trackedPosition = trackedDuration
                saveResume()
                showEnd(true)
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // "Stop at the end" pauses on the last frame (pauseAtEndOfMediaItems).
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) showEnd(true)
        }

        // Only real playback clears the end: play() on an ended player changes playWhenReady but plays nothing.
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) showEnd(false)
        }

        override fun onTracksChanged(tracks: Tracks) = checkAudioTracks(tracks)

        override fun onVideoSizeChanged(videoSize: VideoSize) = followVideoOrientation(videoSize)

        override fun onPlayerError(error: PlaybackException) {
            showError(network?.errorMessage(this@VideoPlayerActivity, error) ?: errorMessage(error))
            binding.resumePill.isVisible = false
            controls.setShown(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = VideoPlayerActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        resumeStore = VideoResumeStore(this)
        resumeDirectly = savedInstanceState == null && intent.getBooleanExtra(EXTRA_RESUME, false)
        val queue = resolveLaunchQueue(savedInstanceState)
        if (queue == null) {
            if (hasLaunchContent()) {
                Toast.makeText(this, R.string.video_player_external_refused, Toast.LENGTH_LONG).show()
            }
            finish()
            return
        }
        // "Picture format at start" from the settings; a recreated screen keeps what the button chose.
        resizeMode = savedInstanceState?.getInt(STATE_RESIZE_MODE)
            ?: when (Settings.VIDEO_RESIZE.valueCompat) {
                "fill" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        setUpWindow()
        setUpControls()
        binding.playerView.resizeMode = resizeMode
        userOrientation = savedInstanceState?.getBoolean(STATE_USER_ORIENTATION) == true
        val network = VideoNetworkPlayback.forUri(queue.items.getOrNull(queue.startIndex)?.mediaId)
        this.network = network
        val player = network.applyTo(ExoPlayer.Builder(this), this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            // As the music player: keeps Wi-Fi up for SMB/SFTP/WebDAV/FTP films.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        this.player = player
        network.attach(player, binding.root)
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
        (savedInstanceState?.getFloat(STATE_SPEED)?.takeIf { it > 0f }
            ?: VideoPrefs.speed(Settings.VIDEO_SPEED.valueCompat)).let {
            player.setPlaybackSpeed(it)
            ViewCompat.setStateDescription(binding.speed, formatSpeed(it))
        }
        // The preferred sound track: the device's language, the film's main track, or German/English.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            when (val language = Settings.VIDEO_AUDIO_LANGUAGE.valueCompat) {
                "original" -> setPreferredAudioRoleFlags(C.ROLE_FLAG_MAIN)
                "de", "en" -> setPreferredAudioLanguage(language)
                else -> setPreferredAudioLanguage(java.util.Locale.getDefault().language)
            }
        }.build()
        applyRepeatMode(VideoRepeatMode.decode(Settings.VIDEO_END.valueCompat))
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
        // During "hold for 2×" the speed to keep is the one before it.
        outState.putFloat(STATE_SPEED, speedBeforeHold.takeIf { it > 0f } ?: player.playbackParameters.speed)
        outState.putInt(STATE_RESIZE_MODE, resizeMode)
        outState.putBoolean(STATE_USER_ORIENTATION, userOrientation)
    }

    override fun onStart() {
        super.onStart()
        if (Settings.VIDEO_REMEMBER_BRIGHTNESS.valueCompat) {
            videoSettings.getFloat(KEY_BRIGHTNESS, -1f).takeIf { it > 0f }?.let { level ->
                window.attributes = window.attributes.apply { screenBrightness = level }
            }
        }
    }

    override fun onStop() {
        // A swipe's brightness belongs to the film: back to the system's when the screen goes (Next Player's
        // rememberPlayerBrightness = false).
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        if (::controls.isInitialized) controls.cancelGestures()
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
        network?.release()
        castSession?.release()
        session?.release()
        session = null
        player?.release()
        player = null
        if (isFinishing) AudioQueueRequests.release(intent.getStringExtra(EXTRA_QUEUE_TOKEN))
        super.onDestroy()
    }

    // While a film plays on the TV, the volume keys set the TV's volume.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val up = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> true
            KeyEvent.KEYCODE_VOLUME_DOWN -> false
            else -> return super.dispatchKeyEvent(event)
        }
        val cast = castSession?.takeIf { it.isCasting } ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) cast.onVolumeKey(up)
        return true
    }

    private fun showCastChooser() {
        if (supportFragmentManager.findFragmentByTag(CastChooserSheet.TAG) == null) {
            CastChooserSheet().show(supportFragmentManager, CastChooserSheet.TAG)
        }
    }

    /** Phone → TV at the current place, TV → phone (paused) where the TV was. */
    private val castHost = object : VideoCastSession.Host {
        override fun castMedia(): VideoCastSession.Media? {
            val player = player ?: return null
            val item = player.currentMediaItem ?: return null
            return VideoCastSession.Media(
                item.mediaId, binding.toolbar.title?.toString().orEmpty(), player.currentPosition.coerceAtLeast(0L),
                player.playWhenReady || player.playbackState == Player.STATE_IDLE
            )
        }

        override fun onCastStarted() {
            player?.pause()
            controls.setShown(true)
        }

        override fun onCastEnded(positionMs: Long?) {
            val player = player ?: return
            if (positionMs != null) {
                player.seekTo(positionMs)
                trackedPosition = positionMs
                saveResume()
            }
            prepareAfterError(player)
            updateUi(player)
        }

        override fun onCastFailed(play: Boolean) {
            val player = player ?: return
            prepareAfterError(player)
            if (play) player.play()
        }
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
     * Edge to edge, system bars hidden with the controls. BEHAVIOR_DEFAULT, not "transient bars by swipe": from
     * Android 12 the back gesture then goes straight through while the bars are hidden; the transient behaviour
     * spent the first edge swipe on showing the bars, so back needed two (Pixel 11 Pro XL, Android 16, 19.11.2026).
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
            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
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
                seekBy(if (forward) jumpMs else -jumpMs)
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
            },
            gesturesAllowed = { !touchExploration && player != null },
            gestureHandler = gestureHandler,
            gestureEnabled = { mode ->
                when (mode) {
                    VideoGestures.Mode.BRIGHTNESS -> Settings.VIDEO_GESTURE_BRIGHTNESS.valueCompat
                    VideoGestures.Mode.VOLUME -> Settings.VIDEO_GESTURE_VOLUME.valueCompat
                    VideoGestures.Mode.SEEK -> Settings.VIDEO_GESTURE_SEEK.valueCompat
                    else -> true
                }
            },
            doubleTapEnabled = { Settings.VIDEO_GESTURE_DOUBLE_TAP.valueCompat },
            holdEnabled = { Settings.VIDEO_GESTURE_HOLD.valueCompat },
            seekStepSeconds = { jumpSeconds }
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
                R.id.actionCast -> showCastChooser()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        binding.playPause.setOnClickListener { playPause() }
        binding.rewind.setOnClickListener { seekBy(-jumpMs) }
        binding.forward.setOnClickListener { seekBy(jumpMs) }
        applyJumpButtons()
        binding.previous.setOnClickListener { player?.let { it.seekToPreviousMediaItem(); prepareAfterError(it) } }
        binding.next.setOnClickListener { player?.let { it.seekToNextMediaItem(); prepareAfterError(it) } }
        binding.speed.setOnClickListener { showSpeedSheet() }
        binding.resize.setOnClickListener { cycleResizeMode() }
        binding.lock.setOnClickListener { controls.setLocked(true) }
        binding.repeat.setOnClickListener {
            val mode = repeatMode.next()
            Settings.VIDEO_END.putValue(mode.name)
            applyRepeatMode(mode)
            showHint(getString(repeatModeName(mode)), Gravity.CENTER)
        }
        binding.endReplay.setOnClickListener {
            player?.let { it.seekTo(0L); it.play() }
        }
        binding.endNext.setOnClickListener {
            player?.let { it.seekToNextMediaItem(); it.play() }
        }
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
        ViewCompat.addAccessibilityAction(
            binding.playerView, getString(R.string.video_player_seek_back_n, jumpSeconds)
        ) { _, _ -> seekBy(-jumpMs); true }
        ViewCompat.addAccessibilityAction(
            binding.playerView, getString(R.string.video_player_seek_ahead_n, jumpSeconds)
        ) { _, _ -> seekBy(jumpMs); true }
        ViewCompat.addAccessibilityAction(binding.playerView, getString(R.string.video_player_show_controls)) { _, _ ->
            controls.setShown(true); true
        }
        ViewCompat.setStateDescription(binding.speed, formatSpeed(1f))
        ViewCompat.setStateDescription(binding.resize, getString(resizeModeName(resizeMode)))
    }

    /** The message with "Open with another app"; the centre buttons step aside so they never cover it. */
    private fun showError(message: String?) {
        message?.let { binding.errorText.text = it }
        binding.errorPanel.isVisible = message != null
        if (message != null) binding.endPanel.isVisible = false
        binding.centerControls.isInvisible = message != null || binding.endPanel.isVisible
    }

    /**
     * Swipes on the picture: brightness of this window only (no permission, back to the system's in onStop), media
     * volume without the system's slider, a seek that shows where it lands and jumps only on release, and 2× while a
     * finger is held. Each shows its value in the hint pill; 0 and 100 % give a haptic tick.
     */
    private val gestureHandler = object : VideoControls.GestureHandler {
        private var startLevel = 0f
        private var startIndex = 0
        private var lastIndex = 0
        private var lastLevel = -1f
        private var startPositionMs = 0L
        private var hinted = false
        private val audioManager by lazy { getSystemService<AudioManager>() }

        override fun begin(mode: VideoGestures.Mode) {
            lastLevel = -1f
            hinted = false
            hideForGesture()
            when (mode) {
                VideoGestures.Mode.BRIGHTNESS -> startLevel = window.attributes.screenBrightness.takeIf { it >= 0f }
                    ?: (android.provider.Settings.System.getInt(
                        contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128
                    ) / 255f)
                VideoGestures.Mode.VOLUME -> {
                    val audio = audioManager ?: return
                    startIndex = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    lastIndex = startIndex
                }
                VideoGestures.Mode.SEEK -> startPositionMs = player?.currentPosition ?: 0L
                else -> {}
            }
        }

        override fun update(mode: VideoGestures.Mode, dx: Float, dy: Float) {
            when (mode) {
                VideoGestures.Mode.BRIGHTNESS -> {
                    val level = VideoGestures.levelAfter(startLevel, dy, binding.root.height)
                    // 0 would turn some screens fully off: the dimmest the window asks for is 1 %.
                    window.attributes = window.attributes.apply { screenBrightness = level.coerceAtLeast(0.01f) }
                    showLevel(level, R.string.video_player_brightness_format)
                }
                VideoGestures.Mode.VOLUME -> {
                    val audio = audioManager ?: return
                    if (audio.isVolumeFixed) return
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val index = VideoGestures.volumeIndex(startIndex, max, dy, binding.root.height)
                    // Device check: setprop log.tag.OrdoVideoGesture DEBUG, logcat -s OrdoVideoGesture.
                    if (android.util.Log.isLoggable(GESTURE_TAG, android.util.Log.DEBUG)) {
                        android.util.Log.d(
                            GESTURE_TAG,
                            "volume dy=$dy height=${binding.root.height} start=$startIndex index=$index/$max"
                        )
                    }
                    if (index != lastIndex) setVolume(audio, index)
                    showLevel(index.toFloat() / max.coerceAtLeast(1), R.string.video_player_volume_format)
                }
                VideoGestures.Mode.SEEK -> {
                    val target = VideoGestures.seekTarget(startPositionMs, dx, binding.root.width, currentDuration())
                    val delta = target - startPositionMs
                    val sign = if (delta < 0) "−" else "+"
                    hinted = true
                    showHint(
                        getString(
                            R.string.video_player_seek_preview_format, formatTime(target),
                            sign + formatTime(kotlin.math.abs(delta))
                        ),
                        GESTURE_PILL, sticky = true
                    )
                }
                else -> {}
            }
        }

        override fun end(mode: VideoGestures.Mode, dx: Float, dy: Float) {
            // The swipe never leaves the stream muted: whatever volume it ends on above 0 is heard.
            if (mode == VideoGestures.Mode.VOLUME) audioManager?.let { if (lastIndex > 0) unmute(it) }
            val slop = ViewConfiguration.get(this@VideoPlayerActivity).scaledTouchSlop.toFloat()
            val jumped = mode == VideoGestures.Mode.SEEK && !VideoGestures.isCancelled(dx, slop)
            if (jumped) {
                player?.seekTo(VideoGestures.seekTarget(startPositionMs, dx, binding.root.width, currentDuration()))
                updateProgress()
            }
            // "Remember brightness": the next video (and the next visit) starts with it.
            if (mode == VideoGestures.Mode.BRIGHTNESS && Settings.VIDEO_REMEMBER_BRIGHTNESS.valueCompat) {
                window.attributes.screenBrightness.takeIf { it >= 0f }
                    ?.let { videoSettings.edit().putFloat(KEY_BRIGHTNESS, it).apply() }
            }
            // Only a value this swipe showed fades out (a fixed-volume device shows none).
            if (hinted) showHint(binding.hint.text.toString(), GESTURE_PILL)
            // A jump leaves the end behind; otherwise what was there comes back.
            restoreAfterGesture(keepEnd = mode != VideoGestures.Mode.SEEK || !jumped)
        }

        override fun hold(held: Boolean) {
            val player = player ?: return
            if (held) {
                speedBeforeHold = player.playbackParameters.speed
                val holdSpeed = VideoPrefs.holdSpeed(Settings.VIDEO_HOLD_SPEED.valueCompat)
                player.setPlaybackSpeed(holdSpeed)
                binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                hideForGesture()
                showHint(formatSpeed(holdSpeed), GESTURE_PILL, sticky = true)
            } else if (speedBeforeHold > 0f) {
                player.setPlaybackSpeed(speedBeforeHold)
                speedBeforeHold = 0f
                showHint(formatSpeed(player.playbackParameters.speed), GESTURE_PILL)
                restoreAfterGesture(keepEnd = true)
            }
        }

        /**
         * Index 0 mutes the stream on Android (Pixel 11, 19.11.2026: still muted after `volume --set 8`); any step
         * above it unmutes first, so a swipe that passed 0 and came back is never silent.
         */
        private fun setVolume(audio: AudioManager, index: Int) {
            if (index > 0) unmute(audio)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
            lastIndex = index
        }

        private fun unmute(audio: AudioManager) {
            if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
        }

        private fun showLevel(level: Float, @StringRes format: Int) {
            val atEnd = level <= 0f || level >= 1f
            if (atEnd && lastLevel != level) {
                binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            }
            lastLevel = level
            hinted = true
            showHint(getString(format, kotlin.math.round(level * 100).toInt()), GESTURE_PILL, sticky = true)
        }
    }

    // While a finger swipes or holds, the centre buttons and the end panel make room for its value.
    private var gestureHid = false
    private var endBeforeGesture = false

    private fun hideForGesture() {
        if (gestureHid) return
        gestureHid = true
        endBeforeGesture = binding.endPanel.isVisible
        binding.endPanel.isVisible = false
        binding.centerControls.isInvisible = true
    }

    private fun restoreAfterGesture(keepEnd: Boolean) {
        if (!gestureHid) return
        gestureHid = false
        if (keepEnd && endBeforeGesture) showEnd(true)
        binding.centerControls.isInvisible = binding.errorPanel.isVisible || binding.endPanel.isVisible
    }

    private fun playPause() {
        val player = player ?: return
        if (binding.endPanel.isVisible) {
            player.seekTo(0L)
            player.play()
            return
        }
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
        // The file name without its extension, as Ordo Video's library shows it.
        val title = player.currentMediaItem?.mediaMetadata?.displayTitle?.toString()
            ?.let { name -> name.substringBeforeLast('.').ifEmpty { name } }
        binding.toolbar.title = title
        binding.playerView.contentDescription = title
        binding.playerView.keepScreenOn = player.isPlaying && Settings.VIDEO_KEEP_SCREEN_ON.valueCompat
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
        // "Don't remember places" in the settings.
        if (Settings.VIDEO_NO_HISTORY.valueCompat) return
        // While "Resume at" is still offered nothing is decided: closing then must not forget the place.
        if (binding.resumePill.isVisible) return
        // Looping one video passes its end again and again: that never makes it "watched".
        if (repeatMode == VideoRepeatMode.ONE &&
            !VideoResumeStore.isWorthKeeping(trackedPosition, trackedDuration)) return
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
            val resume = VideoPrefs.resume(Settings.VIDEO_RESUME.valueCompat)
            if (Settings.VIDEO_NO_HISTORY.valueCompat || resume == VideoPrefs.Resume.NEVER) return@launch
            val position = resumeStore.read(key) ?: return@launch
            val player = player ?: return@launch
            if (player.currentMediaItem?.mediaId != mediaId || player.currentPosition >= position) return@launch
            if (resumeDirectly || resume == VideoPrefs.Resume.ALWAYS) {
                resumeDirectly = false
                player.seekTo(position)
                return@launch
            }
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
        when (VideoPrefs.orientation(Settings.VIDEO_ORIENTATION.valueCompat)) {
            // "Like the system": the player never asks; the rotate button still can.
            VideoPrefs.Orientation.SYSTEM -> return
            VideoPrefs.Orientation.LANDSCAPE -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                return
            }
            VideoPrefs.Orientation.VIDEO -> {}
        }
        // Like Next Player's "video orientation": wide films turn the phone, tall ones keep it upright.
        requestedOrientation = if (videoSize.width * videoSize.pixelWidthHeightRatio > videoSize.height) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    /** The ±buttons show and say the jump from the settings: 5, 10 and 30 have their own symbol, 15 the plain one. */
    private fun applyJumpButtons() {
        val seconds = jumpSeconds
        val (back, ahead) = when (seconds) {
            5 -> R.drawable.video_replay_5_icon_24dp to R.drawable.video_forward_5_icon_24dp
            30 -> R.drawable.video_replay_30_icon_24dp to R.drawable.video_forward_30_icon_24dp
            15 -> R.drawable.reset_icon_white_24dp to R.drawable.reset_icon_white_24dp
            else -> R.drawable.video_replay_10_icon_24dp to R.drawable.video_forward_10_icon_24dp
        }
        binding.rewind.setImageResource(back)
        binding.forward.setImageResource(ahead)
        // The plain replay arrow, mirrored, points ahead.
        binding.forward.scaleX = if (seconds == 15) -1f else 1f
        binding.rewind.contentDescription = getString(R.string.video_player_seek_back_n, seconds)
        binding.forward.contentDescription = getString(R.string.video_player_seek_ahead_n, seconds)
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

    /** [sticky]: stays while a finger moves it (a swipe's value), fades after the finger lifts. */
    private fun showHint(text: String, gravity: Int, sticky: Boolean = false) {
        val hint = binding.hint
        hint.text = text
        hint.updateLayoutParams<FrameLayout.LayoutParams> {
            // A gesture's value sits in the upper third, below the title bar, clear of the centre buttons.
            if (gravity == GESTURE_PILL) {
                this.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = maxOf(binding.resumePill.marginTop, binding.root.height / 6)
            } else {
                this.gravity = gravity
                topMargin = 0
            }
        }
        hint.animate().cancel()
        hint.alpha = 1f
        hint.isVisible = true
        handler.removeCallbacks(hideHint)
        if (sticky) return
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
        dialog.showExpanded()
    }

    /**
     * The reason in words. A decoder that cannot start often surfaces only as "Unexpected runtime error" (W90, HEVC
     * 10-bit, 19.11.2026), so the cause chain and the tracks are read too: then the message names the format.
     */
    private fun errorMessage(error: PlaybackException): String {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(10).toList()
        val decoderFailed = error.errorCode in 4000..4999 || causes.any {
            it is MediaCodecRenderer.DecoderInitializationException || it is MediaCodecDecoderException ||
                it is android.media.MediaCodec.CodecException
        }
        if (decoderFailed) {
            val format = player?.currentTracks?.groups
                ?.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.mediaTrackGroup?.getFormat(0)
            val mimeType = format?.sampleMimeType
                ?: causes.firstNotNullOfOrNull { (it as? MediaCodecRenderer.DecoderInitializationException)?.mimeType }
            videoCodecName(mimeType, format?.codecs, format?.colorInfo?.lumaBitdepth ?: -1)?.let { (name, tenBit) ->
                val label = if (tenBit) getString(R.string.video_player_ten_bit_format, name) else name
                return getString(R.string.video_player_error_device_format, label)
            }
        }
        return getString(
            when (error.errorCode) {
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> R.string.video_player_error_damaged
                // Media3's code ranges: 2xxx input/output, 3xxx parsing, 4xxx decoding.
                in 2000..2999 -> R.string.video_player_error_read
                in 3000..4999 -> R.string.video_player_error_format
                else -> if (decoderFailed) R.string.video_player_error_format else R.string.video_player_error
            }
        )
    }

    /**
     * A sound track this device has no decoder for (AC3 on budget tablets) plays as silence without a word: say so
     * calmly, and when another sound track can play, take that one.
     */
    private fun checkAudioTracks(tracks: Tracks) {
        val player = player ?: return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        // Per track, not per group: a group counts as supported as soon as any of its tracks is.
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { group -> (0 until group.length).map { group to it } }
        if (audio.isEmpty()) return
        val selected = audio.firstOrNull { (group, index) -> group.isTrackSelected(index) }
        if (selected != null && selected.first.isTrackSupported(selected.second)) return
        val unsupported = selected ?: audio.firstOrNull { (group, index) -> !group.isTrackSupported(index) } ?: return
        val format = unsupported.first.getTrackFormat(unsupported.second)
        val name = audioCodecName(format.sampleMimeType) ?: format.sampleMimeType ?: return
        val playable = audio.firstOrNull { (group, index) -> group.isTrackSupported(index) }
        if (playable != null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(playable.first.mediaTrackGroup, playable.second))
                .build()
        }
        if (audioNoticeFor == mediaId) return
        audioNoticeFor = mediaId
        binding.notice.text = getString(
            if (playable != null) {
                R.string.video_player_audio_switched_format
            } else {
                R.string.video_player_audio_unsupported_format
            },
            name
        )
        binding.notice.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = binding.resumePill.marginTop }
        binding.notice.isVisible = true
        handler.removeCallbacks(hideNotice)
        handler.postDelayed(hideNotice, NOTICE_MS)
    }

    /** What happens at the end: stay on the last frame, repeat this video, or go on to the next. */
    private fun applyRepeatMode(mode: VideoRepeatMode) {
        repeatMode = mode
        player?.repeatMode = if (mode == VideoRepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player?.pauseAtEndOfMediaItems = mode == VideoRepeatMode.STOP
        binding.repeat.setImageResource(
            when (mode) {
                VideoRepeatMode.STOP -> R.drawable.repeat_icon_24dp
                VideoRepeatMode.ONE -> R.drawable.repeat_one_icon_24dp
                VideoRepeatMode.NEXT -> R.drawable.video_autoplay_icon_24dp
            }
        )
        // Off reads as off: the plain repeat symbol, dimmed like the music player's inactive toggles.
        binding.repeat.alpha = if (mode == VideoRepeatMode.STOP) INACTIVE_CONTROL_ALPHA else 1f
        ViewCompat.setStateDescription(binding.repeat, getString(repeatModeName(mode)))
    }

    private fun repeatModeName(mode: VideoRepeatMode): Int = when (mode) {
        VideoRepeatMode.STOP -> R.string.video_player_repeat_stop
        VideoRepeatMode.ONE -> R.string.video_player_repeat_one
        VideoRepeatMode.NEXT -> R.string.video_player_repeat_next
    }

    /** The end of a video that does not go on: watch again, or the next one; the centre buttons step aside. */
    private fun showEnd(shown: Boolean) {
        binding.endPanel.isVisible = shown
        binding.endNext.isVisible = shown && player?.hasNextMediaItem() == true
        binding.centerControls.isInvisible = binding.errorPanel.isVisible || shown
        if (shown) controls.setShown(true)
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

    /**
     * "Video info": what plays (from the player's tracks, any source), plus size, folder and recording date for Ordo's
     * own paths. The raw file properties stay one tap further ("All file details").
     */
    private fun showFileInfo() {
        val player = player ?: return
        val path = currentPath()
        val name = player.currentMediaItem?.mediaMetadata?.displayTitle?.toString()
        val info = VideoInfo.fromTracks(
            binding.toolbar.title?.toString().orEmpty(), name, currentDuration(), player.currentTracks,
            getString(R.string.video_info_not_playable)
        )
        lifecycleScope.launch {
            val withFile = if (path == null) info else withContext(Dispatchers.IO) {
                runCatching {
                    val attributes = path.readAttributes(BasicFileAttributes::class.java)
                    info.copy(
                        size = attributes.size(), folder = path.parent?.toString(),
                        recordedMs = if (path.isLinuxPath) VideoInfo.recordedAt(path.toString())
                        else attributes.lastModifiedTime().toMillis()
                    )
                }.getOrDefault(info)
            }
            if (isFinishing || supportFragmentManager.isStateSaved) return@launch
            showVideoInfo(withFile, path)
        }
    }

    companion object {
        private const val EXTRA_QUEUE_TOKEN = "me.zhanghai.android.files.viewer.video.extra.QUEUE_TOKEN"
        private const val EXTRA_RESUME = "me.zhanghai.android.files.viewer.video.extra.RESUME"
        private const val STATE_MEDIA_ID = "mediaId"
        private const val STATE_INDEX = "index"
        private const val STATE_POSITION = "position"
        private const val STATE_PLAY_WHEN_READY = "playWhenReady"
        private const val STATE_SPEED = "speed"
        private const val STATE_RESIZE_MODE = "resizeMode"
        private const val STATE_USER_ORIENTATION = "userOrientation"
        private const val SESSION_ID = "video"
        private const val SEEK_MAX = 1_000
        private const val PROGRESS_UPDATE_INTERVAL_MS = 1_000L
        // The place is written every 5 s while the film plays (and when it stops or changes).
        private const val SAVE_EVERY_TICKS = 5
        private const val RESUME_PILL_MS = 5_000L
        private const val HINT_MS = 700L
        private const val FADE_MS = 200L
        private const val DISABLED_CONTROL_ALPHA = 0.38f
        private const val GESTURE_TAG = "OrdoVideoGesture"
        // Not a real gravity: showHint's marker for "a gesture's value, upper third".
        private const val GESTURE_PILL = -1
        private const val SETTINGS_NAME = "video_settings"
        private const val KEY_BRIGHTNESS = "brightness"
        private const val NOTICE_MS = 6_000L
        private const val INACTIVE_CONTROL_ALPHA = 0.70f
        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

        /** The folder's videos stay in this process; the intent only carries the token to take them over. */
        fun putExtras(intent: Intent, paths: List<Path>, position: Int, resume: Boolean = false) {
            intent.putExtra(
                EXTRA_QUEUE_TOKEN, AudioQueueRequests.register(AudioQueueRequests.Request(paths, position))
            )
            // Only seeks within the file the token names; a forged extra alone opens nothing.
            if (resume) intent.putExtra(EXTRA_RESUME, true)
        }
    }
}
