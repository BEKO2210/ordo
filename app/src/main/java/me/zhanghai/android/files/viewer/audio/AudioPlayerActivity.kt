/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import me.zhanghai.android.files.theme.custom.AppAccentActivity
import me.zhanghai.android.files.settings.SettingLiveData
import android.animation.ArgbEvaluator
import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.core.os.bundleOf
import androidx.core.view.MenuItemCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.mediarouter.app.SystemOutputSwitcherDialogController
import coil.dispose
import coil.load
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.common.util.concurrent.ListenableFuture
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.databinding.AudioPlayerActivityBinding
import me.zhanghai.android.files.player.AudioPlaybackService
import me.zhanghai.android.files.player.AudioPlayerCommands
import me.zhanghai.android.files.player.AudioQueueRequests
import me.zhanghai.android.files.player.PlaybackStateStore
import me.zhanghai.android.files.player.pathUriExists
import me.zhanghai.android.files.player.restorableState
import me.zhanghai.android.files.player.cast.CastManager
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.settings.Settings as OrdoSettings
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.viewer.audio.lyrics.Lyrics
import me.zhanghai.android.files.viewer.audio.lyrics.LyricsRepository
import me.zhanghai.android.files.util.startActivitySafe

/**
 * Exported for ACTION_VIEW of every audio type. Ordo's own screens pass a queue through [AudioQueueRequests] and only a token in
 * the intent; from any other caller only a `content://` URI with a read grant is played, and every path extra is ignored.
 */
@OptIn(UnstableApi::class)
class AudioPlayerActivity : AppActivity(), AppAccentActivity {
    override val appAccent: SettingLiveData<String>
        get() = OrdoSettings.MUSIC_ACCENT

    private lateinit var binding: AudioPlayerActivityBinding
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var artworkJob: Job? = null
    private var artworkKey: String? = null
    private lateinit var atmosphere: PlayerAtmosphereView
    private var colorAnimator: ValueAnimator? = null
    /** accent, onAccent, container: what the controls show now (animated between songs). */
    private var colors = IntArray(3)
    private var seekBubble: TextView? = null
    private var wavePlaying: Boolean? = null
    private lateinit var lyricsView: PlayerLyricsView
    private var lyrics: Lyrics? = null
    private var lyricsKey: String? = null
    private var lyricsJob: Job? = null
    private var lyricsShowPlay = true
    // Opened from a song's lyrics pictogram in the library: the lyrics show as soon as they are loaded.
    private var openLyricsPending = false
    private val lyricsBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hideLyrics()
    }
    // While the lyrics show and music plays: four looks a second at the position. Views change only with the line.
    private val lyricsTicker = object : Runnable {
        override fun run() {
            controller?.let { lyricsView.setPosition(it.currentPosition) }
            if (lyricsView.isVisible && controller?.isPlaying == true) handler.postDelayed(this, LYRICS_TICK_MS)
        }
    }
    private var queueStarted = false
    private var launchQueue: LaunchQueue? = null
    private var progressDescription: String? = null
    private var userSeeking = false
    private val handler = Handler(Looper.getMainLooper())
    // The heart: favourites are Ordo Music's playlist Music/Playlists/Favorites.m3u8.
    private val playlists by lazy {
        @Suppress("DEPRECATION")
        PlaylistStore(java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_MUSIC), "Playlists"))
    }
    private var favoritePath: String? = null
    private var favorite = false
    private val editTagsLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) TagEditUndo.offer(this, binding.root, lifecycleScope, it.data) {}
        }
    private lateinit var castChip: Chip
    private val castListener: () -> Unit = { updateCastState() }

    private fun nextProgressDelay(): Long {
        val position = controller?.currentPosition ?: 0L
        val speed = controller?.playbackParameters?.speed?.takeIf { it > 0f } ?: 1f
        return ((PROGRESS_UPDATE_INTERVAL_MS - position % PROGRESS_UPDATE_INTERVAL_MS) / speed).toLong().coerceIn(50L, 1_000L) + 20L
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgress()
            // The backdrop drifts one step in the same frame as the time, so it costs no frame of its own.
            atmosphere.advance()
            if (controller?.isPlaying == true) handler.postDelayed(this, nextProgressDelay())
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_AVAILABLE_COMMANDS_CHANGED
                )) {
                updateUi(player)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Snackbar.make(binding.root, R.string.audio_player_error, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = AudioPlayerActivityBinding.inflate(layoutInflater)
        // The atmosphere lies behind every layout variant of the player without touching their XML.
        atmosphere = PlayerAtmosphereView(this)
        lyricsView = PlayerLyricsView(
            this, onSeek = { controller?.seekTo(it) }, onClose = ::hideLyrics,
            onPlayPause = { controller?.let { Util.handlePlayPauseButtonAction(it) } }
        ).apply {
            isVisible = false
        }
        setContentView(FrameLayout(this).apply {
            addView(atmosphere, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // Before the player: it gets the window insets first (before Android 11 the player's fitsSystemWindows
            // consumes them), and the player is invisible while the lyrics show, so the order does not matter there.
            addView(lyricsView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(binding.root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        onBackPressedDispatcher.addCallback(this, lyricsBack)
        colors = themeColors()
        // A hairline (24 % onSurface) keeps a pale cover apart from a pale backdrop (review panel 18.11.2026).
        // The cover's shadow and clip follow its own rounded corners. Its outline was a plain rectangle, so the bottom
        // corners carried a square shadow while the top ones looked round (owner's macro photo, 19.11.2026).
        binding.artworkCard.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, binding.artworkCard.radius)
            }
        }
        binding.artworkCard.clipToOutline = true
        // No elevation shadow: on the Tab and the W90 its corners stayed square at the bottom (1:1 crops) while the
        // cover's own corners are round, also with a rounded outline. Flat, with the hairline, all four corners match.
        binding.artworkCard.cardElevation = 0f
        binding.artworkCard.elevation = 0f
        binding.artworkCard.strokeWidth = resources.displayMetrics.density.toInt().coerceAtLeast(1)
        binding.artworkCard.strokeColor = ColorUtils.setAlphaComponent(
            themeColor(com.google.android.material.R.attr.colorOnSurface), COVER_OUTLINE_ALPHA
        )
        applyColors(colors)
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateArtworkSize() }
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.actionCast) {
                showCastChooser()
                true
            } else if (item.itemId == R.id.actionQueue) {
                PlayerQueueSheet().show(supportFragmentManager, PlayerQueueSheet.TAG)
                true
            } else if (item.itemId == R.id.actionLyrics) {
                showLyrics()
                true
            } else if (item.itemId == R.id.actionSound) {
                AudioSoundSettingsSheet().show(supportFragmentManager, AudioSoundSettingsSheet.TAG)
                true
            } else if (item.itemId == R.id.actionFavorite) {
                val path = favoritePath
                if (path != null) {
                    lifecycleScope.launch {
                        val now = withContext(Dispatchers.IO) { runCatching { playlists.toggleFavorite(path) }.getOrNull() }
                        if (now == null) return@launch
                        favorite = now
                        showFavorite()
                        Snackbar.make(
                            binding.root, if (now) R.string.music_favorite_added else R.string.music_favorite_removed,
                            Snackbar.LENGTH_SHORT
                        ).show()
                    }
                }
                true
            } else if (item.itemId == R.id.actionEditTags) {
                currentFilePath()?.let {
                    editTagsLauncher.launch(MusicTagEditorActivity.createIntent(this, listOf(it)))
                }
                true
            } else if (item.itemId == R.id.actionOutput) {
                // Android 6–10 has no system output picker; connecting a Bluetooth speaker is the next best thing.
                if (!SystemOutputSwitcherDialogController.showDialog(this)) {
                    startActivitySafe(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
                true
            } else false
        }
        binding.previous.setOnClickListener { controller?.seekToPreviousMediaItem() }
        binding.playPause.setOnClickListener {
            controller?.let { Util.handlePlayPauseButtonAction(it) }
        }
        binding.next.setOnClickListener { controller?.seekToNextMediaItem() }
        binding.shuffle.setOnClickListener {
            controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
        }
        binding.repeat.setOnClickListener { cycleRepeatMode() }
        // The slider keeps room for its thumb beside the track; pulled out by that much, the track lines up with the
        // times below it.
        (binding.seekBar.layoutParams as ViewGroup.MarginLayoutParams).apply {
            marginStart = -binding.seekBar.trackSidePadding
            marginEnd = -binding.seekBar.trackSidePadding
        }
        // From Android 11 the flowing wave draws into a small surface laid exactly over the slider, so it never redraws
        // the window (a child surface there stays under dialogs and sheets). It only shows the wave: it takes no
        // touches and is hidden from accessibility, both stay with the slider.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val seek = binding.seekBar
            val parent = seek.parent as ViewGroup
            val index = parent.indexOfChild(seek)
            val params = seek.layoutParams
            parent.removeViewAt(index)
            val frame = FrameLayout(this)
            frame.addView(seek, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            val surface = SurfaceView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            frame.addView(surface, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            parent.addView(frame, index, params)
            seek.setWaveSurface(surface)
        }
        binding.seekBar.setLabelFormatter { formatTime((currentDuration() * it / SEEK_MAX).toLong()) }
        binding.seekBar.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val duration = currentDuration()
            val position = (duration * value / SEEK_MAX).toLong()
            binding.elapsed.text = formatTime(position)
            binding.remaining.text = formatRemaining(duration - position)
            // Accessibility actions and keys step the bar without a touch; they seek straight away.
            if (!userSeeking && duration > 0) controller?.seekTo(position)
        }
        binding.seekBar.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                val duration = currentDuration()
                if (duration > 0) controller?.seekTo((duration * slider.value / SEEK_MAX).toLong())
                userSeeking = false
            }
        })
        setUpDoubleTapSeek()
        setUpPills()
        castChip = createCastChip()
        queueStarted = savedInstanceState?.getBoolean(STATE_QUEUE_STARTED) == true
        if (!queueStarted) {
            launchQueue = resolveLaunchQueue()
            if (launchQueue == null && hasLaunchContent()) {
                Toast.makeText(this, R.string.audio_player_external_refused, Toast.LENGTH_LONG).show()
                finish()
                return
            }
        }
        showPlaceholder()
        openLyricsPending = savedInstanceState == null && intent.getBooleanExtra(EXTRA_LYRICS, false)
        // Ordo Music's menu opens Sound or the sleep timer over the player.
        if (savedInstanceState == null && intent.hasExtra(EXTRA_SOUND_SHEET)) {
            AudioSoundSettingsSheet().apply {
                arguments = bundleOf(
                    AudioSoundSettingsSheet.ARG_SLEEP_TIMER to intent.getBooleanExtra(EXTRA_SOUND_SHEET, false)
                )
            }.show(supportFragmentManager, AudioSoundSettingsSheet.TAG)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_QUEUE_STARTED, queueStarted)
    }

    override fun onDestroy() {
        if (isFinishing && !queueStarted) {
            AudioQueueRequests.release(intent.getStringExtra(EXTRA_QUEUE_TOKEN))
        }
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        connectController()
        CastManager.addListener(castListener)
        updateCastState()
    }

    override fun onStop() {
        CastManager.removeListener(castListener)
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(lyricsTicker)
        artworkJob?.cancel()
        colorAnimator?.cancel()
        artworkKey = null
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun showCastChooser() {
        if (supportFragmentManager.findFragmentByTag(CastChooserSheet.TAG) == null) {
            CastChooserSheet().show(supportFragmentManager, CastChooserSheet.TAG)
        }
    }

    /** "Playing on Wohnzimmer" above the title while casting; its × ends casting. Added here so every layout has it. */
    private fun createCastChip(): Chip {
        val chip = Chip(this).apply {
            setChipIconResource(R.drawable.cast_connected_icon_24dp)
            chipIconTint = ColorStateList.valueOf(themeColor(androidx.appcompat.R.attr.colorPrimary))
            isCloseIconVisible = true
            closeIconContentDescription = getString(R.string.cast_stop)
            setOnCloseIconClickListener { CastManager.disconnect() }
            setOnClickListener { showCastChooser() }
            isVisible = false
        }
        val parent = binding.title.parent as android.view.ViewGroup
        parent.addView(
            chip, parent.indexOfChild(binding.title),
            android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = resources.getDimensionPixelSize(R.dimen.phi_13)
            }
        )
        return chip
    }

    private fun updateCastState() {
        val device = CastManager.device
        castChip.isVisible = device != null
        if (device != null) {
            castChip.text = getString(
                if (CastManager.phase == CastManager.Phase.CONNECTING) R.string.cast_chip_connecting else R.string.cast_chip,
                device.name
            )
        }
        // The TV stays a TV (next to "Play on…", a second cast symbol would read as the same button); casting tints it.
        binding.toolbar.menu.findItem(R.id.actionCast)?.let { item ->
            MenuItemCompat.setIconTintList(
                item,
                if (device != null) ColorStateList.valueOf(themeColor(androidx.appcompat.R.attr.colorPrimary)) else null
            )
        }
    }

    private fun connectController() {
        val future = MediaController.Builder(
            this,
            SessionToken(this, ComponentName(this, AudioPlaybackService::class.java))
        ).setListener(object : MediaController.Listener {
            override fun onExtrasChanged(controller: MediaController, extras: Bundle) = showOutput(extras)
        }).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future || isFinishing) return@addListener
            val newController = try {
                future.get()
            } catch (e: Exception) {
                e.printStackTrace()
                Snackbar.make(binding.root, R.string.audio_player_connection_error, Snackbar.LENGTH_LONG)
                    .show()
                return@addListener
            }
            controller = newController
            newController.addListener(playerListener)
            showOutput(newController.sessionExtras)
            // A setting can be persisted while the asynchronous controller connection is still pending.
            applySoundSettings()
            (supportFragmentManager.findFragmentByTag(AudioSoundSettingsSheet.TAG)
                as? AudioSoundSettingsSheet)?.refreshStatus()
            startLaunchQueue(newController)
            resumeSavedQueue(newController)
            updateUi(newController)
        }, ContextCompat.getMainExecutor(this))
    }

    /** The output badge: bit-perfect with bits and rate, or offload; hidden otherwise. */
    private fun showOutput(extras: Bundle) {
        val label = extras.getString(AudioPlayerCommands.EXTRA_OUTPUT)
        binding.outputBadge.text = label
        binding.outputBadge.isVisible = label != null
        // The label changes when a song goes bit-perfect or back: the open sound sheet follows.
        (supportFragmentManager.findFragmentByTag(AudioSoundSettingsSheet.TAG) as? AudioSoundSettingsSheet)
            ?.refreshStatus()
    }

        private class LaunchQueue(val items: List<MediaItem>, val startIndex: Int)

    private fun hasLaunchContent(): Boolean =
        intent.getStringExtra(EXTRA_QUEUE_TOKEN) != null || intent.data != null

    /**
     * Ordo's own queue comes through its token. Anything else is an external caller: only a `content://` URI that
     * carries a read grant counts, and the path extras (which another app could point at any Ordo file) are ignored.
     */
    private fun resolveLaunchQueue(): LaunchQueue? =
        try {
            val request = AudioQueueRequests.from(intent.getStringExtra(EXTRA_QUEUE_TOKEN))
            if (request != null) {
                LaunchQueue(
                    request.paths.map { AudioPlaybackService.mediaItem(it.toUri().toString()) },
                    request.startIndex
                ).takeIf { it.items.isNotEmpty() }
                    ?.let { LaunchQueue(it.items, it.startIndex.coerceIn(it.items.indices)) }
            } else {
                intent.data?.takeIf(::isAllowedExternalUri)
                    ?.let { LaunchQueue(listOf(AudioPlaybackService.mediaItem(it.toString())), 0) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

    private fun isAllowedExternalUri(uri: Uri): Boolean {
        val authority = uri.authority
        return uri.scheme == ContentResolver.SCHEME_CONTENT
            && !authority.isNullOrEmpty()
            // "userId@authority" would slip past the own-authority check.
            && '@' !in authority
            && !isOwnAuthority(authority)
            && intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
    }

    // Ordo's own providers read with Ordo's access; a caller must never be able to point at them.
    private fun isOwnAuthority(authority: String): Boolean =
        authority.startsWith(BuildConfig.APPLICATION_ID)
            || authority.startsWith(packageName)
            || authority == getString(R.string.file_provider_authority)
            || authority == getString(R.string.app_provider_authority)

    private fun startLaunchQueue(player: MediaController) {
        val queue = launchQueue ?: return
        launchQueue = null
        queueStarted = true
        AudioQueueRequests.release(intent.getStringExtra(EXTRA_QUEUE_TOKEN))
        player.setMediaItems(queue.items, queue.startIndex, 0L)
        player.prepare()
        player.play()
    }

    /**
     * Opened from the music icon: when the session holds nothing (the process was gone), the queue Ordo saved is
     * put back, paused. A running session is left alone. With nothing left to resume the player closes and the
     * library below it shows.
     */
    private fun resumeSavedQueue(player: MediaController) {
        if (queueStarted || !intent.getBooleanExtra(EXTRA_RESUME, false)) return
        queueStarted = true
        if (player.mediaItemCount > 0) return
        val context = applicationContext
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                PlaybackStateStore(context).read()?.let { restorableState(it, ::pathUriExists) }
            }
            if (isFinishing || isDestroyed) return@launch
            if (state == null) {
                finish()
                return@launch
            }
            if (controller !== player || player.mediaItemCount > 0) return@launch
            player.setMediaItems(state.paths.map { AudioPlaybackService.mediaItem(it) }, state.currentIndex, state.positionMs)
            player.prepare()
        }
    }

    /** The playing song as a local file of Ordo's own; null for a content:// song of another app. */
    private fun currentFilePath(): String? =
        controller?.currentMediaItem?.mediaId
            ?.let { runCatching { java.net.URI.create(it) }.getOrNull() }
            ?.takeIf { it.scheme == "file" }?.path

    private fun showFavorite() {
        binding.toolbar.menu.findItem(R.id.actionFavorite)?.apply {
            isVisible = favoritePath != null
            setIcon(if (favorite) R.drawable.favorite_icon_white_24dp else R.drawable.favorite_border_icon_white_24dp)
            setTitle(if (favorite) R.string.music_favorite_remove else R.string.music_favorite_add)
        }
    }

    private fun updateUi(player: Player) {
        val filePath = currentFilePath()
        binding.toolbar.menu.findItem(R.id.actionEditTags)?.isVisible = filePath != null
        if (filePath != favoritePath) {
            favoritePath = filePath
            favorite = false
            showFavorite()
            if (filePath != null) {
                lifecycleScope.launch {
                    val isFavorite = withContext(Dispatchers.IO) {
                        runCatching { playlists.isFavorite(filePath) }.getOrDefault(false)
                    }
                    if (favoritePath == filePath) {
                        favorite = isFavorite
                        showFavorite()
                    }
                }
            }
        }
        val metadata = player.mediaMetadata
        updateLyrics(player)
        val item = player.currentMediaItem
        binding.title.text = metadata.title?.takeIf { it.isNotBlank() }
            ?: metadata.displayTitle?.takeIf { it.isNotBlank() }
            ?: item?.mediaMetadata?.displayTitle
            ?: getString(R.string.audio_player_unknown_title)
        val artist = metadata.artist?.takeIf { it.isNotBlank() }
            ?: getString(R.string.audio_player_unknown_artist)
        binding.artist.text = metadata.albumTitle?.takeIf { it.isNotBlank() }?.let {
            getString(R.string.audio_player_artist_album_format, artist, it)
        } ?: artist
        // Kept in every layout for stable view binding; artist and album read as one calm line.
        binding.album.isVisible = false
        val showPlay = Util.shouldShowPlayButton(player)
        lyricsShowPlay = showPlay
        lyricsView.setSong(binding.title.text, binding.artist.text)
        lyricsView.setPlayback(showPlay, colors[0], colors[1])
        updatePills(player)
        binding.playPause.setImageResource(
            if (showPlay) R.drawable.play_icon_24dp else R.drawable.pause_icon_24dp
        )
        binding.playPause.contentDescription = getString(
            if (showPlay) R.string.audio_player_play else R.string.audio_player_pause
        )
        binding.previous.isEnabled = player.hasPreviousMediaItem()
        binding.next.isEnabled = player.hasNextMediaItem()
        binding.previous.alpha = if (binding.previous.isEnabled) 1f else DISABLED_CONTROL_ALPHA
        binding.next.alpha = if (binding.next.isEnabled) 1f else DISABLED_CONTROL_ALPHA
        binding.seekBar.isEnabled = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            && player.duration != C.TIME_UNSET
        // The wave flows only while music plays and flattens on pause; the atmosphere drifts with it.
        // Only on a change: every call restarts the wave's spring and phase.
        val effects = MusicEffects.level(this)
        MusicEffects.waveFrameMs(effects).takeIf { it > 0 }?.let { binding.seekBar.phasePeriodMs = it }
        atmosphere.frameMs = MusicEffects.backdropFrameMs(effects)
        val flows = player.isPlaying && effects != MusicEffects.Level.SAVER
        if (wavePlaying != flows) {
            wavePlaying = flows
            binding.seekBar.setWaveEnabled(flows)
        }
        atmosphere.playing = flows
        binding.shuffle.isActivated = player.shuffleModeEnabled
        binding.shuffle.contentDescription = getString(
            if (player.shuffleModeEnabled) R.string.audio_player_shuffle_on else R.string.audio_player_shuffle_off
        )
        setToggleAppearance(binding.shuffle, binding.shuffleIndicator, player.shuffleModeEnabled)
        binding.repeat.isActivated = player.repeatMode != Player.REPEAT_MODE_OFF
        setToggleAppearance(
            binding.repeat, binding.repeatIndicator, player.repeatMode != Player.REPEAT_MODE_OFF
        )
        binding.repeat.setImageResource(
            if (player.repeatMode == Player.REPEAT_MODE_ONE) {
                R.drawable.repeat_one_icon_24dp
            } else {
                R.drawable.repeat_icon_24dp
            }
        )
        binding.repeat.contentDescription = getString(
            when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> R.string.audio_player_repeat_one
                Player.REPEAT_MODE_ALL -> R.string.audio_player_repeat_all
                else -> R.string.audio_player_repeat_off
            }
        )
        handler.removeCallbacks(progressUpdater)
        updateProgress()
        if (player.isPlaying) handler.postDelayed(progressUpdater, nextProgressDelay())
        updateArtwork(item, metadata)
    }

    private fun updateProgress() {
        if (userSeeking) return
        val player = controller ?: return
        val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        val position = player.currentPosition.coerceIn(0L, duration.coerceAtLeast(0L))
        binding.seekBar.value = if (duration > 0) (position * SEEK_MAX / duration).toFloat() else 0f
        binding.elapsed.text = formatTime(position)
        binding.remaining.text = formatRemaining(duration - position)
        val description = getString(
            R.string.audio_player_progress_format, formatTime(position), formatTime(duration)
        )
        if (description != progressDescription) {
            progressDescription = description
            ViewCompat.setStateDescription(binding.seekBar, description)
        }
    }

    private fun updateArtwork(item: MediaItem?, metadata: MediaMetadata) {
        val key = "${item?.mediaId}:${metadata.artworkData?.contentHashCode()}"
        if (artworkKey == key) return
        artworkKey = key
        artworkJob?.cancel()
        binding.artwork.dispose()
        metadata.artworkData?.let { artwork ->
            loadArtwork(artwork)
            return
        }
        val pathUri = item?.mediaId?.takeIf { it.isNotBlank() } ?: run {
            showPlaceholder()
            return
        }
        artworkJob = lifecycleScope.launch {
            val pathWithAttributes = withContext(Dispatchers.IO) {
                try {
                    val path = java8.nio.file.Paths.get(java.net.URI.create(pathUri))
                    path to path.readAttributes(BasicFileAttributes::class.java)
                } catch (_: Exception) {
                    null
                }
            }
            if (pathWithAttributes != null) loadArtwork(pathWithAttributes) else showPlaceholder()
        }
    }

    private fun loadArtwork(data: Any) {
        binding.artwork.apply {
            setPadding(0, 0, 0, 0)
            scaleType = ImageView.ScaleType.CENTER_CROP
            ImageViewCompat.setImageTintList(this, null)
            load(data) {
                // The background wash reads the cover's pixels; a hardware bitmap can't be read and failed the load.
                allowHardware(false)
                crossfade(Motion.any)
                error(R.drawable.file_audio_icon)
                listener(
                    onSuccess = { _, result -> updateArtworkWash(result.drawable) },
                    onError = { _, _ -> showPlaceholder() }
                )
            }
        }
    }

    private fun showPlaceholder() {
        updateBackgroundWash()
        binding.artworkCard.setCardBackgroundColor(
            themeColor(
                com.google.android.material.R.attr.colorSurfaceContainerHigh,
                com.google.android.material.R.attr.colorSurfaceVariant
            )
        )
        binding.artwork.apply {
            dispose()
            // A calm, large note (a third of the cover) instead of a tiny glyph that looked like a loading state.
            scaleType = ImageView.ScaleType.FIT_CENTER
            val padding = binding.artworkContainer.width.takeIf { it > 0 }?.let { it / 3 }
                ?: resources.getDimensionPixelSize(R.dimen.audio_player_artwork_padding)
            setPadding(padding, padding, padding, padding)
            setImageResource(R.drawable.file_audio_icon)
            ImageViewCompat.setImageTintList(
                this,
                ColorStateList.valueOf(
                    themeColor(
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                        android.R.attr.textColorSecondary
                    )
                )
            )
        }
    }

    /**
     * Off the main thread: the cover's colour roles (WCAG-checked, else the theme's), its blurred atmosphere and the
     * veil strength that keeps text and controls readable over it. Then everything fades over in 300 ms.
     */
    private fun updateArtworkWash(drawable: Drawable) {
        val key = artworkKey
        val source = (drawable as? BitmapDrawable)?.bitmap
            ?: drawable.toBitmap(CoverBlur.SIZE, CoverBlur.SIZE, Bitmap.Config.ARGB_8888)
        val surface = themeColor(com.google.android.material.R.attr.colorSurface)
        val onSurface = themeColor(com.google.android.material.R.attr.colorOnSurface)
        val secondaryText = themeColor(android.R.attr.textColorSecondary)
        val fallback = themeColors()
        val isLight = ColorUtils.calculateLuminance(surface) > 0.5
        val coverColors = OrdoSettings.MUSIC_COVER_COLORS.valueCompat
        artworkJob = lifecycleScope.launch {
            val (target, look) = withContext(Dispatchers.Default) {
                val size = CoverBlur.SIZE
                val small = Bitmap.createScaledBitmap(source, size, size, true)
                val pixels = IntArray(size * size)
                small.getPixels(pixels, 0, size, 0, 0, size, size)
                if (small !== source) small.recycle()
                // "Colours from the cover" off (Ordo Musik › Settings): the accent, the cover still behind.
                val scheme = AudioPlayerColorWash.seed(pixels)?.takeIf { coverColors }
                    ?.let { AudioPlayerColorWash.scheme(it, surface, onSurface, isLight) }
                val target = scheme?.let {
                    intArrayOf(it.accent, it.onAccent, AudioPlayerColorWash.inactiveTrack(it.accent, it.tint))
                } ?: fallback
                CoverBlur.blur(pixels, size, size)
                val veil = scheme?.tint ?: surface
                val fields = scheme?.let { intArrayOf(it.accent, it.container) } ?: IntArray(0)
                val alpha = AudioPlayerColorWash.veilAlpha(
                    pixels.filterIndexed { i, _ -> i % VEIL_SAMPLE_STEP == 0 }.toIntArray() + fields,
                    veil, intArrayOf(onSurface, secondaryText), target[0],
                    if (isLight) MIN_VEIL_LIGHT else MIN_VEIL_DARK
                )
                target to PlayerAtmosphereView.Look(
                    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888), fields, veil, alpha
                )
            }
            if (artworkKey != key || isFinishing) return@launch
            atmosphere.show(look)
            fadeColors(target)
        }
    }

    /** No cover: the theme's own colours over the plain surface. */
    private fun updateBackgroundWash() {
        val surface = themeColor(com.google.android.material.R.attr.colorSurface)
        atmosphere.show(PlayerAtmosphereView.Look(null, IntArray(0), surface, 1f))
        fadeColors(themeColors())
    }

    private fun themeColors(): IntArray {
        val primary = themeColor(androidx.appcompat.R.attr.colorPrimary)
        return intArrayOf(
            primary, themeColor(com.google.android.material.R.attr.colorOnPrimary),
            AudioPlayerColorWash.inactiveTrack(primary, themeColor(com.google.android.material.R.attr.colorSurface))
        )
    }

    private fun fadeColors(target: IntArray) {
        colorAnimator?.cancel()
        if (!Motion.any || target.contentEquals(colors)) {
            applyColors(target)
            return
        }
        val start = colors.copyOf()
        val evaluator = ArgbEvaluator()
        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BACKGROUND_CROSSFADE_DURATION_MS
            addUpdateListener { animator ->
                val f = animator.animatedFraction
                applyColors(IntArray(target.size) { evaluator.evaluate(f, start[it], target[it]) as Int })
            }
            start()
        }
    }

    /** Loads the song's lyrics (a .lrc beside it or embedded) once per song; the toolbar offers them only if found. */
    private fun updateLyrics(player: Player) {
        val mediaId = player.currentMediaItem?.mediaId
        if (mediaId != lyricsKey) {
            lyricsKey = mediaId
            lyrics = null
            lyricsJob?.cancel()
            binding.toolbar.menu.findItem(R.id.actionLyrics)?.isVisible = false
            if (mediaId != null) {
                lyricsJob = lifecycleScope.launch {
                    val loaded = runCatching { LyricsRepository.load(mediaId) }.getOrNull()
                    if (lyricsKey != mediaId) return@launch
                    lyrics = loaded
                    binding.toolbar.menu.findItem(R.id.actionLyrics)?.isVisible = loaded != null
                    controller?.let { updatePills(it) }
                    if (loaded != null && openLyricsPending) {
                        openLyricsPending = false
                        showLyrics()
                    }
                    if (lyricsView.isVisible) {
                        if (loaded == null) hideLyrics() else showLyrics()
                    }
                }
            } else if (lyricsView.isVisible) {
                hideLyrics()
            }
        }
        handler.removeCallbacks(lyricsTicker)
        if (lyricsView.isVisible) lyricsTicker.run()
    }

    private fun showLyrics() {
        val lyrics = lyrics ?: return
        lyricsView.show(lyrics, themeColor(com.google.android.material.R.attr.colorOnSurface),
            themeColor(android.R.attr.textColorSecondary),
            atmosphere.veilColor.takeIf { it != 0 } ?: themeColor(com.google.android.material.R.attr.colorSurface))
        lyricsView.setSong(binding.title.text, binding.artist.text)
        if (!lyricsView.isVisible) {
            lyricsView.isVisible = true
            lyricsBack.isEnabled = true
            fade(lyricsView, binding.root)
        }
        controller?.let { lyricsView.setPosition(it.currentPosition) }
        handler.removeCallbacks(lyricsTicker)
        lyricsTicker.run()
    }

    private fun hideLyrics() {
        if (!lyricsView.isVisible) return
        lyricsBack.isEnabled = false
        handler.removeCallbacks(lyricsTicker)
        binding.root.isVisible = true
        fade(binding.root, lyricsView)
    }

    /** Cross-fades from [from] to [to] in 200 ms (instantly without motion); [from] ends up gone or invisible. */
    private fun fade(to: View, from: View) {
        to.isVisible = true
        if (!Motion.any) {
            to.alpha = 1f
            from.visibility = if (from === binding.root) View.INVISIBLE else View.GONE
            return
        }
        to.alpha = 0f
        to.animate().alpha(1f).setDuration(LYRICS_FADE_MS).start()
        from.animate().alpha(0f).setDuration(LYRICS_FADE_MS).withEndAction {
            from.visibility = if (from === binding.root) View.INVISIBLE else View.GONE
            from.alpha = 1f
        }.start()
    }

    private fun applyColors(newColors: IntArray) {
        colors = newColors
        val (accent, onAccent, container) = newColors
        if (::lyricsView.isInitialized) lyricsView.setPlayback(lyricsShowPlay, accent, onAccent)
        val accentList = ColorStateList.valueOf(accent)
        ViewCompat.setBackgroundTintList(binding.playPause, accentList)
        ImageViewCompat.setImageTintList(binding.playPause, ColorStateList.valueOf(onAccent))
        binding.seekBar.trackActiveTintList = accentList
        binding.seekBar.thumbTintList = accentList
        binding.seekBar.trackInactiveTintList = ColorStateList.valueOf(container)
        ViewCompat.setBackgroundTintList(binding.shuffleIndicator, accentList)
        ViewCompat.setBackgroundTintList(binding.repeatIndicator, accentList)
        if (binding.shuffle.isActivated) ImageViewCompat.setImageTintList(binding.shuffle, accentList)
        if (binding.repeat.isActivated) ImageViewCompat.setImageTintList(binding.repeat, accentList)
        seekBubble?.let {
            ViewCompat.setBackgroundTintList(it, accentList)
            it.setTextColor(onAccent)
        }
    }

    private fun currentDuration(): Long =
        controller?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L

    /**
     * Swiping the cover sideways changes the song ([CoverSwipe]). Double-tapping its left or right half jumps 10 s back
     * or ahead (as in video players): the precise way
     * that never needs the seek bar. A short bubble on that side names the jump.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setUpDoubleTapSeek() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val player = controller ?: return false
                val duration = currentDuration()
                if (duration <= 0) return false
                val rightHalf = e.x > binding.artworkCard.width / 2
                val forward = rightHalf != (binding.artworkCard.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                val delta = if (forward) DOUBLE_TAP_SEEK_MS else -DOUBLE_TAP_SEEK_MS
                player.seekTo((player.currentPosition + delta).coerceIn(0L, duration))
                binding.artworkCard.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                showSeekBubble(forward)
                updateProgress()
                return true
            }
        })
        binding.artworkContainer.cameraDistance = COVER_CAMERA_DISTANCE * resources.displayMetrics.density
        val swipe = CoverSwipe(
            binding.artworkContainer,
            canGo = { forward ->
                controller?.let { if (forward) it.hasNextMediaItem() else it.hasPreviousMediaItem() } == true
            },
            go = { forward -> controller?.let { if (forward) it.seekToNextMediaItem() else it.seekToPreviousMediaItem() } }
        )
        binding.artworkCard.setOnTouchListener { _, event ->
            swipe.onTouch(event)
            detector.onTouchEvent(event)
            true
        }
    }

    private lateinit var lyricsPill: com.google.android.material.button.MaterialButton
    private lateinit var queuePill: com.google.android.material.button.MaterialButton

    /**
     * Under the title: labelled pills for the lyrics (only when the song has them) and for what plays next, so both
     * are seen and not hidden behind small toolbar icons (as Apple Music and Spotify show them in the player).
     */
    private fun setUpPills() {
        val parent = binding.artist.parent as android.widget.LinearLayout
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = binding.artist.gravity
        }
        fun pill(id: Int, icon: Int, onClick: () -> Unit) =
            com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                this.id = id
                setIconResource(icon)
                iconSize = resources.getDimensionPixelSize(R.dimen.phi_21)
                cornerRadius = resources.getDimensionPixelSize(R.dimen.phi_21)
                minHeight = resources.getDimensionPixelSize(R.dimen.phi_row_min) - resources.getDimensionPixelSize(
                    R.dimen.phi_5)
                maxLines = 1
                isAllCaps = false
                isVisible = false
                setOnClickListener { onClick() }
            }
        lyricsPill = pill(R.id.lyricsChip, R.drawable.tag_lyrics_icon_white_24dp) { showLyrics() }
            .apply { setText(R.string.music_lyrics) }
        queuePill = pill(R.id.queueChip, R.drawable.queue_music_icon_white_24dp) {
            PlayerQueueSheet().show(supportFragmentManager, PlayerQueueSheet.TAG)
        }
        val gap = resources.getDimensionPixelSize(R.dimen.phi_8)
        row.addView(lyricsPill, android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = gap })
        row.addView(queuePill)
        parent.addView(row, parent.indexOfChild(binding.album) + 1, android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.phi_13) })
    }

    private fun updatePills(player: Player) {
        if (!::queuePill.isInitialized) return
        lyricsPill.isVisible = lyrics != null
        val next = (player.mediaItemCount - player.currentMediaItemIndex - 1).coerceAtLeast(0)
        // Always there, as the toolbar icon was: the queue also opens with a single song (to add more).
        queuePill.isVisible = player.mediaItemCount > 0
        queuePill.text = getString(R.string.music_queue_next, next)
        val stroke = ColorStateList.valueOf(ColorUtils.setAlphaComponent(colors[0], PILL_STROKE_ALPHA))
        for (pill in listOf(lyricsPill, queuePill)) {
            pill.strokeColor = stroke
            pill.iconTint = ColorStateList.valueOf(colors[0])
            pill.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
        }
    }

    private fun showSeekBubble(forward: Boolean) {
        val bubble = seekBubble ?: TextView(this).also { view ->
            view.setBackgroundResource(R.drawable.audio_player_seek_bubble)
            view.setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle2)
            val padding = resources.getDimensionPixelSize(R.dimen.phi_13)
            view.setPadding(padding, padding / 2, padding, padding / 2)
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            binding.artworkContainer.addView(
                view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            seekBubble = view
            applyColors(colors)
        }
        bubble.text = getString(if (forward) R.string.audio_player_seek_forward else R.string.audio_player_seek_back)
        val margin = resources.getDimensionPixelSize(R.dimen.phi_21)
        (bubble.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.CENTER_VERTICAL or if (forward) Gravity.END else Gravity.START
            setMargins(margin, 0, margin, 0)
        }
        bubble.requestLayout()
        bubble.animate().cancel()
        bubble.alpha = 1f
        bubble.animate().alpha(0f).setStartDelay(SEEK_BUBBLE_MS).setDuration(if (Motion.any) 200L else 0L).start()
    }

    private fun setToggleAppearance(button: ImageView, indicator: android.view.View, active: Boolean) {
        button.alpha = if (active) 1f else INACTIVE_CONTROL_ALPHA
        ImageViewCompat.setImageTintList(
            button,
            ColorStateList.valueOf(
                if (active) colors[0] else themeColor(com.google.android.material.R.attr.colorOnSurface)
            )
        )
        indicator.isVisible = active
    }

    private fun themeColor(attribute: Int, fallback: Int? = null): Int {
        val value = TypedValue()
        if (!theme.resolveAttribute(attribute, value, true)) {
            return MaterialColors.getColor(binding.root, checkNotNull(fallback))
        }
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun updateArtworkSize() {
        val container = binding.artworkContainer
        val parent = container.parent as? android.view.View ?: return
        if (parent.width == 0 || parent.height == 0) return
        val size = if (container.tag == ARTWORK_LAYOUT_SPLIT) {
            val inset = resources.getDimensionPixelSize(R.dimen.audio_player_split_artwork_inset)
            minOf(parent.width - inset, parent.height - inset)
        } else {
            val contentHeight = binding.root.height - binding.toolbar.height
            // Inside the column's padding: wider, the cover was clipped at it and lost its rounded corners.
            minOf(parent.width - parent.paddingLeft - parent.paddingRight,
                (contentHeight * MAX_COLUMN_ARTWORK_HEIGHT_FRACTION).toInt())
        }.coerceAtLeast(resources.getDimensionPixelSize(R.dimen.audio_player_artwork_min_size))
        val params = container.layoutParams
        if (params.width == size && params.height == size) return
        params.width = size
        params.height = size
        if (params is android.widget.LinearLayout.LayoutParams) params.gravity = Gravity.CENTER_HORIZONTAL
        container.layoutParams = params
    }

    private fun cycleRepeatMode() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    /** The session's player for the queue sheet. */
    internal fun queuePlayer(): Player? = controller

    internal fun applySoundSettings(onApplied: (() -> Unit)? = null) {
        val player = controller ?: return
        val future = player.sendCustomCommand(AudioPlayerCommands.APPLY_SETTINGS, Bundle.EMPTY)
        if (onApplied != null) future.addListener(onApplied, ContextCompat.getMainExecutor(this))
    }

    internal fun querySoundStatus(callback: (SoundStatus) -> Unit) {
        val player = controller ?: return callback(SoundStatus())
        val future = player.sendCustomCommand(AudioPlayerCommands.GET_SOUND_STATUS, Bundle.EMPTY)
        future.addListener({
            val extras = runCatching { future.get().extras }.getOrNull() ?: return@addListener
            callback(
                SoundStatus(
                    extras.getBoolean(AudioPlayerCommands.EXTRA_EQ_AVAILABLE),
                    extras.getBoolean(AudioPlayerCommands.EXTRA_DP),
                    extras.getBoolean(AudioPlayerCommands.EXTRA_INPUT_GAIN_AVAILABLE),
                    extras.getInt(AudioPlayerCommands.EXTRA_LEGACY_BANDS),
                    extras.getBoolean(AudioPlayerCommands.EXTRA_BASS_AVAILABLE),
                    extras.getBoolean(AudioPlayerCommands.EXTRA_CLARITY_AVAILABLE),
                    extras.getBoolean(AudioPlayerCommands.EXTRA_HEADPHONES),
                    extras.getInt(AudioPlayerCommands.EXTRA_HIGHS_CUTOFF),
                    extras.getFloat(AudioPlayerCommands.EXTRA_QUIET_BASS_DB, 0f),
                    extras.getBoolean(AudioPlayerCommands.EXTRA_SLEEP_ACTIVE),
                    extras.getInt(AudioPlayerCommands.EXTRA_SLEEP_MINUTES),
                    extras.getBoolean(AudioPlayerCommands.EXTRA_END_OF_TRACK),
                    extras.getLong(AudioPlayerCommands.EXTRA_SLEEP_REMAINING_MS),
                    extras.getBoolean(AudioPlayerCommands.EXTRA_SONG_NOT_BIT_PERFECT),
                    extras.getBoolean(AudioPlayerCommands.EXTRA_SONG_BIT_PERFECT)
                )
            )
        }, ContextCompat.getMainExecutor(this))
    }

    internal fun setSleepTimer(minutes: Int, endOfTrack: Boolean, onApplied: (() -> Unit)? = null) {
        val player = controller ?: return
        val future = player.sendCustomCommand(
            AudioPlayerCommands.SLEEP_TIMER,
            bundleOf(
                AudioPlayerCommands.EXTRA_SLEEP_MINUTES to minutes,
                AudioPlayerCommands.EXTRA_END_OF_TRACK to endOfTrack
            )
        )
        if (onApplied != null) future.addListener(onApplied, ContextCompat.getMainExecutor(this))
    }

    internal data class SoundStatus(
        val equalizerAvailable: Boolean = false,
        val usingDynamicsProcessing: Boolean = false,
        val inputGainAvailable: Boolean = false,
        val legacyBandCount: Int = 0,
        val bassBoostAvailable: Boolean = false,
        val clarityAvailable: Boolean = false,
        val headphones: Boolean = false,
        val highsCutoffHz: Int = 0,
        /** The bass lift "full sound when quiet" gives at the current volume, in dB. */
        val quietBassDb: Float = 0f,
        val sleepActive: Boolean = false,
        val sleepMinutes: Int = 0,
        val sleepAtEndOfTrack: Boolean = false,
        val sleepRemainingMs: Long = 0L,
        val songNotBitPerfect: Boolean = false,
        /** This song plays bit-perfect: every Ordo stage is bypassed (the saved settings stay). */
        val songBitPerfect: Boolean = false
    )

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = totalSeconds % 3_600L / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

    private fun formatRemaining(milliseconds: Long): String = "−${formatTime(milliseconds)}"

    companion object {
        private const val EXTRA_QUEUE_TOKEN =
            "me.zhanghai.android.files.viewer.audio.extra.QUEUE_TOKEN"
        private const val EXTRA_RESUME = "me.zhanghai.android.files.viewer.audio.extra.RESUME"
        private const val EXTRA_SOUND_SHEET = "me.zhanghai.android.files.viewer.audio.extra.SOUND_SHEET"
        private const val EXTRA_LYRICS = "me.zhanghai.android.files.viewer.audio.extra.LYRICS"
        private const val STATE_QUEUE_STARTED = "queueStarted"
        private const val SEEK_MAX = 1_000
        // Once per displayed second, on the second: the times only change then, and a screen that redraws twice a
        // second never goes idle for accessibility tools (uiautomator could not read the player while it played).
        private const val PROGRESS_UPDATE_INTERVAL_MS = 1_000L
        private const val INACTIVE_CONTROL_ALPHA = 0.70f
        private const val DISABLED_CONTROL_ALPHA = 0.38f
        private const val VEIL_SAMPLE_STEP = 3
        private const val MIN_VEIL_LIGHT = 0.42f
        private const val COVER_OUTLINE_ALPHA = 0x3d
        private const val MIN_VEIL_DARK = 0.4f
        private const val DOUBLE_TAP_SEEK_MS = 10_000L
        private const val LYRICS_TICK_MS = 250L
        private const val COVER_CAMERA_DISTANCE = 8_000f
        private const val LYRICS_FADE_MS = 200L
        private const val PILL_STROKE_ALPHA = 0x8a
        private const val SEEK_BUBBLE_MS = 600L
        private const val BACKGROUND_CROSSFADE_DURATION_MS = 300L
        private const val MAX_COLUMN_ARTWORK_HEIGHT_FRACTION = 0.60f
        private const val ARTWORK_LAYOUT_SPLIT = "split"

        /** The queue stays in this process; the intent only carries the token to take it over. */
        fun putExtras(intent: Intent, paths: List<Path>, position: Int) {
            intent.putExtra(
                EXTRA_QUEUE_TOKEN,
                AudioQueueRequests.register(AudioQueueRequests.Request(paths, position))
            )
        }

        /** For the music icon: shows the saved queue, paused, unless the session already holds one. */
        fun createResumeIntent(context: Context): Intent =
            Intent(context, AudioPlayerActivity::class.java).putExtra(EXTRA_RESUME, true)

        /** The player with the Sound sheet open, or scrolled to its sleep timer. */
        fun withSoundSheet(intent: Intent, sleepTimer: Boolean): Intent =
            intent.putExtra(EXTRA_SOUND_SHEET, sleepTimer)

        /** The player that opens straight into the song's lyrics (the library's lyrics pictogram). */
        fun withLyrics(intent: Intent): Intent = intent.putExtra(EXTRA_LYRICS, true)

        fun createSessionIntent(context: Context): Intent =
            Intent(context, AudioPlayerActivity::class.java)
    }
}
