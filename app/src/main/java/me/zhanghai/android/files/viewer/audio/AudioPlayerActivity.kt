/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.os.bundleOf
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
import com.google.android.material.color.MaterialColors
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
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.util.startActivitySafe

/**
 * Exported for ACTION_VIEW of every audio type. Ordo's own screens pass a queue through [AudioQueueRequests] and only a token in
 * the intent; from any other caller only a `content://` URI with a read grant is played, and every path extra is ignored.
 */
@OptIn(UnstableApi::class)
class AudioPlayerActivity : AppActivity() {
    private lateinit var binding: AudioPlayerActivityBinding
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var artworkJob: Job? = null
    private var artworkKey: String? = null
    private var backgroundAnimator: ValueAnimator? = null
    private var backgroundColor = 0
    private var queueStarted = false
    private var launchQueue: LaunchQueue? = null
    private var progressDescription: String? = null
    private var userSeeking = false
    private val handler = Handler(Looper.getMainLooper())
    private val editTagsLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) TagEditUndo.offer(this, binding.root, lifecycleScope, it.data) {}
        }

    private fun nextProgressDelay(): Long {
        val position = controller?.currentPosition ?: 0L
        val speed = controller?.playbackParameters?.speed?.takeIf { it > 0f } ?: 1f
        return ((PROGRESS_UPDATE_INTERVAL_MS - position % PROGRESS_UPDATE_INTERVAL_MS) / speed).toLong().coerceIn(50L, 1_000L) + 20L
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgress()
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
        setContentView(binding.root)
        backgroundColor = themeColor(com.google.android.material.R.attr.colorSurface)
        binding.root.setBackgroundColor(backgroundColor)
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateArtworkSize() }
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.actionSound) {
                AudioSoundSettingsSheet().show(supportFragmentManager, AudioSoundSettingsSheet.TAG)
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
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = controller?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
                binding.elapsed.text = formatTime(duration * progress / SEEK_MAX)
                binding.remaining.text = formatRemaining(duration - duration * progress / SEEK_MAX)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                controller?.let { player ->
                    val duration = player.duration
                    if (duration > 0 && duration != C.TIME_UNSET) {
                        player.seekTo(duration * seekBar.progress / SEEK_MAX)
                    }
                }
                userSeeking = false
            }
        })
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
    }

    override fun onStop() {
        handler.removeCallbacks(progressUpdater)
        artworkJob?.cancel()
        backgroundAnimator?.cancel()
        artworkKey = null
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun connectController() {
        val future = MediaController.Builder(
            this,
            SessionToken(this, ComponentName(this, AudioPlaybackService::class.java))
        ).buildAsync()
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
            // A setting can be persisted while the asynchronous controller connection is still pending.
            applySoundSettings()
            (supportFragmentManager.findFragmentByTag(AudioSoundSettingsSheet.TAG)
                as? AudioSoundSettingsSheet)?.refreshStatus()
            startLaunchQueue(newController)
            resumeSavedQueue(newController)
            updateUi(newController)
        }, ContextCompat.getMainExecutor(this))
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

    private fun updateUi(player: Player) {
        binding.toolbar.menu.findItem(R.id.actionEditTags)?.isVisible = currentFilePath() != null
        val metadata = player.mediaMetadata
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
        binding.seekBar.progress = if (duration > 0) (position * SEEK_MAX / duration).toInt() else 0
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
        updateBackgroundWash(null)
        binding.artworkCard.setCardBackgroundColor(
            themeColor(
                com.google.android.material.R.attr.colorSurfaceContainerHigh,
                com.google.android.material.R.attr.colorSurfaceVariant
            )
        )
        binding.artwork.apply {
            dispose()
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val padding = resources.getDimensionPixelSize(R.dimen.audio_player_artwork_padding)
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

    private fun updateArtworkWash(drawable: Drawable) {
        val bitmap = drawable.toBitmap(ARTWORK_SAMPLE_SIZE, ARTWORK_SAMPLE_SIZE, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        updateBackgroundWash(AudioPlayerColorWash.averageArgb(pixels))
    }

    private fun updateBackgroundWash(artworkColor: Int?) {
        val surface = themeColor(com.google.android.material.R.attr.colorSurface)
        val onSurface = themeColor(com.google.android.material.R.attr.colorOnSurface)
        val target = artworkColor?.let {
            AudioPlayerColorWash.blendWithSurface(surface, it, onSurface)
        } ?: surface
        backgroundAnimator?.cancel()
        if (!Motion.any || backgroundColor == target) {
            backgroundColor = target
            binding.root.setBackgroundColor(target)
            return
        }
        backgroundAnimator = ValueAnimator.ofArgb(backgroundColor, target).apply {
            duration = BACKGROUND_CROSSFADE_DURATION_MS
            addUpdateListener {
                backgroundColor = it.animatedValue as Int
                binding.root.setBackgroundColor(backgroundColor)
            }
            start()
        }
    }

    private fun setToggleAppearance(button: ImageView, indicator: android.view.View, active: Boolean) {
        button.alpha = if (active) 1f else INACTIVE_CONTROL_ALPHA
        ImageViewCompat.setImageTintList(
            button,
            ColorStateList.valueOf(
                themeColor(
                    if (active) androidx.appcompat.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOnSurface
                )
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
            minOf(parent.width, (contentHeight * MAX_COLUMN_ARTWORK_HEIGHT_FRACTION).toInt())
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
                    extras.getFloat(AudioPlayerCommands.EXTRA_VOLUME_FRACTION, 1f),
                    extras.getBoolean(AudioPlayerCommands.EXTRA_SLEEP_ACTIVE),
                    extras.getInt(AudioPlayerCommands.EXTRA_SLEEP_MINUTES),
                    extras.getBoolean(AudioPlayerCommands.EXTRA_END_OF_TRACK),
                    extras.getLong(AudioPlayerCommands.EXTRA_SLEEP_REMAINING_MS)
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
        val volumeFraction: Float = 1f,
        val sleepActive: Boolean = false,
        val sleepMinutes: Int = 0,
        val sleepAtEndOfTrack: Boolean = false,
        val sleepRemainingMs: Long = 0L
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
        private const val STATE_QUEUE_STARTED = "queueStarted"
        private const val SEEK_MAX = 1_000
        // Once per displayed second, on the second: the times only change then, and a screen that redraws twice a
        // second never goes idle for accessibility tools (uiautomator could not read the player while it played).
        private const val PROGRESS_UPDATE_INTERVAL_MS = 1_000L
        private const val INACTIVE_CONTROL_ALPHA = 0.70f
        private const val DISABLED_CONTROL_ALPHA = 0.38f
        private const val ARTWORK_SAMPLE_SIZE = 24
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

        fun createSessionIntent(context: Context): Intent =
            Intent(context, AudioPlayerActivity::class.java)
    }
}
