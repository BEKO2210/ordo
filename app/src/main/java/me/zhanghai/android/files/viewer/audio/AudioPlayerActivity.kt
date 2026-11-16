/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.dispose
import coil.load
import com.google.android.material.snackbar.Snackbar
import com.google.common.util.concurrent.ListenableFuture
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.databinding.AudioPlayerActivityBinding
import me.zhanghai.android.files.player.AudioPlaybackService
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.util.extraPathList

@UnstableApi
class AudioPlayerActivity : AppActivity() {
    private lateinit var binding: AudioPlayerActivityBinding
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var artworkJob: Job? = null
    private var artworkKey: String? = null
    private var shouldStartIntentQueue = false
    private var userSeeking = false
    private val handler = Handler(Looper.getMainLooper())

    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
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
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.previous.setOnClickListener { controller?.seekToPreviousMediaItem() }
        binding.playPause.setOnClickListener {
            controller?.let { if (it.playWhenReady) it.pause() else it.play() }
        }
        binding.next.setOnClickListener { controller?.seekToNextMediaItem() }
        binding.shuffle.setOnClickListener { player ->
            controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
            player.announceForAccessibility(
                getString(
                    if (controller?.shuffleModeEnabled == true) {
                        R.string.audio_player_shuffle_on
                    } else {
                        R.string.audio_player_shuffle_off
                    }
                )
            )
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
        shouldStartIntentQueue = savedInstanceState == null && hasPlayableIntent()
        showPlaceholder()
    }

    override fun onStart() {
        super.onStart()
        connectController()
        handler.post(progressUpdater)
    }

    override fun onStop() {
        handler.removeCallbacks(progressUpdater)
        artworkJob?.cancel()
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
            if (shouldStartIntentQueue) {
                shouldStartIntentQueue = false
                startIntentQueue(newController)
            }
            updateUi(newController)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun hasPlayableIntent(): Boolean = intent.extraPathList.isNotEmpty() || intent.data != null

    private fun startIntentQueue(player: MediaController) {
        val paths = intent.extraPathList
        val items: List<MediaItem>
        val startIndex: Int
        if (paths.isNotEmpty()) {
            items = paths.map { AudioPlaybackService.mediaItem(it.toUri().toString()) }
            startIndex = intent.getIntExtra(EXTRA_POSITION, 0).coerceIn(items.indices)
        } else {
            val uri = intent.data ?: return
            items = listOf(AudioPlaybackService.mediaItem(uri.toString()))
            startIndex = 0
        }
        player.setMediaItems(items, startIndex, 0L)
        player.prepare()
        player.play()
    }

    private fun updateUi(player: Player) {
        val metadata = player.mediaMetadata
        val item = player.currentMediaItem
        binding.title.text = metadata.title?.takeIf { it.isNotBlank() }
            ?: metadata.displayTitle?.takeIf { it.isNotBlank() }
            ?: item?.mediaMetadata?.displayTitle
            ?: getString(R.string.audio_player_unknown_title)
        binding.artist.text = metadata.artist?.takeIf { it.isNotBlank() }
            ?: getString(R.string.audio_player_unknown_artist)
        binding.album.text = metadata.albumTitle
        binding.album.isVisible = !metadata.albumTitle.isNullOrBlank()
        binding.playPause.setImageResource(
            if (player.playWhenReady) R.drawable.pause_icon_24dp else R.drawable.play_icon_24dp
        )
        binding.playPause.contentDescription = getString(
            if (player.playWhenReady) R.string.audio_player_pause else R.string.audio_player_play
        )
        binding.previous.isEnabled = player.hasPreviousMediaItem()
        binding.next.isEnabled = player.hasNextMediaItem()
        binding.seekBar.isEnabled = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            && player.duration != C.TIME_UNSET
        binding.shuffle.isActivated = player.shuffleModeEnabled
        binding.shuffle.alpha = if (player.shuffleModeEnabled) 1f else INACTIVE_CONTROL_ALPHA
        binding.repeat.isActivated = player.repeatMode != Player.REPEAT_MODE_OFF
        binding.repeat.alpha =
            if (player.repeatMode != Player.REPEAT_MODE_OFF) 1f else INACTIVE_CONTROL_ALPHA
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
        updateProgress()
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
            load(data) {
                crossfade(Motion.any)
                error(R.drawable.file_audio_icon)
                listener(onError = { _, _ -> showPlaceholder() })
            }
        }
    }

    private fun showPlaceholder() {
        binding.artwork.apply {
            dispose()
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val padding = resources.getDimensionPixelSize(R.dimen.audio_player_artwork_padding)
            setPadding(padding, padding, padding, padding)
            setImageResource(R.drawable.file_audio_icon)
        }
    }

    private fun cycleRepeatMode() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            binding.repeat.announceForAccessibility(binding.repeat.contentDescription)
        }
    }

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
        private const val EXTRA_POSITION =
            "me.zhanghai.android.files.viewer.audio.extra.POSITION"
        private const val SEEK_MAX = 1_000
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
        private const val INACTIVE_CONTROL_ALPHA = 0.62f

        fun putExtras(intent: Intent, paths: List<Path>, position: Int) {
            intent.extraPathList = paths
            intent.putExtra(EXTRA_POSITION, position)
        }

        fun createSessionIntent(context: Context): Intent =
            Intent(context, AudioPlayerActivity::class.java)
    }
}
