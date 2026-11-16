/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.media3.session.SessionCommands
import java8.nio.file.Files
import java8.nio.file.Paths
import me.zhanghai.android.files.viewer.audio.AudioPlayerActivity

@UnstableApi
class AudioPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var stateStore: PlaybackStateStore
    private val handler = Handler(Looper.getMainLooper())
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var lastQueue: List<String>? = null
    // Index in the player's queue to index in the persisted queue, or -1 for items that are never persisted.
    private var persistedIndices = IntArray(0)
    private val savePosition = object : Runnable {
        override fun run() {
            persistPosition()
            if (::player.isInitialized && player.isPlaying) {
                handler.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                persistQueue()
            }
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED
                )) {
                persistPosition()
            }
            if (events.containsAny(
                    Player.EVENT_REPEAT_MODE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED
                )) {
                stateStore.writeModes(
                    PlaybackStateStore.Modes(player.shuffleModeEnabled, player.repeatMode)
                )
            }
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                handler.removeCallbacks(savePosition)
                persistPosition()
                if (player.isPlaying) handler.postDelayed(savePosition, POSITION_SAVE_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        stateStore = PlaybackStateStore(this)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val modes = stateStore.readModes()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(PathDataSource.Factory(this)))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            // Holds the Wi-Fi lock too, so SFTP/SMB/WebDAV/FTP playback survives the screen turning off.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                // The modes of the last session are restored here, not from a session callback.
                shuffleModeEnabled = modes.shuffleEnabled
                repeatMode = modes.repeatMode
                addListener(playerListener)
            }
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    AudioPlayerActivity.createSessionIntent(this),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistPosition()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(savePosition)
        if (::player.isInitialized) {
            persistPosition()
            player.removeListener(playerListener)
        }
        if (::mediaSession.isInitialized) mediaSession.release()
        if (::player.isInitialized) player.release()
        ioExecutor.shutdown()
        super.onDestroy()
    }

    /** `content://` items come from an external launch; their URI grant ends with that task, so they are not kept. */
    private fun isPersistable(item: MediaItem): Boolean {
        val scheme = item.localConfiguration?.uri?.scheme
        return scheme != null && scheme != ContentResolver.SCHEME_CONTENT
    }

    private fun persistQueue() {
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        val count = player.mediaItemCount
        val indices = IntArray(count)
        val paths = ArrayList<String>(count)
        for (index in 0 until count) {
            val item = player.getMediaItemAt(index)
            if (isPersistable(item)) {
                indices[index] = paths.size
                paths += item.mediaId
            } else {
                indices[index] = -1
            }
        }
        persistedIndices = indices
        if (paths == lastQueue) return
        lastQueue = paths
        if (paths.isEmpty()) stateStore.clearQueue() else stateStore.writeQueue(paths)
    }

    private fun persistPosition() {
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        val index = persistedIndices.getOrNull(player.currentMediaItemIndex) ?: return
        if (index < 0) return
        stateStore.writePosition(index, player.currentPosition.coerceAtLeast(0L))
    }

    /** Drops queue entries whose file is gone and moves the start to the next one that is left. */
    private fun restorableState(state: PlaybackStateStore.State):
        MediaSession.MediaItemsWithStartPosition {
        val kept = ArrayList<String>(state.paths.size)
        var startIndex = -1
        var currentSurvived = false
        state.paths.forEachIndexed { index, pathUri ->
            if (!exists(pathUri)) return@forEachIndexed
            if (startIndex < 0 && index >= state.currentIndex) {
                startIndex = kept.size
                currentSurvived = index == state.currentIndex
            }
            kept += pathUri
        }
        if (kept.isEmpty()) {
            stateStore.clearQueue()
            return MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
        }
        if (startIndex < 0) startIndex = kept.lastIndex
        val positionMs = if (currentSurvived) state.positionMs else 0L
        if (kept.size != state.paths.size) {
            stateStore.writeQueue(kept)
            stateStore.writePosition(startIndex, positionMs)
        }
        return MediaSession.MediaItemsWithStartPosition(kept.map(::mediaItem), startIndex, positionMs)
    }

    private fun exists(pathUri: String): Boolean =
        try {
            val uri = URI.create(pathUri)
            uri.scheme != ContentResolver.SCHEME_CONTENT && Files.exists(Paths.get(uri))
        } catch (_: Exception) {
            false
        }

    private inner class SessionCallback : MediaSession.Callback {
        // Only Ordo's own player screen may hand the session items to open: their URIs are read with Ordo's own
        // file access. Everyone else, the media notification and external controllers included, only controls playback.
        private fun isOwnController(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): Boolean =
            controller.packageName == packageName && !session.isMediaNotificationController(controller)

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            if (!isOwnController(session, controller)) {
                builder
                    .setAvailableSessionCommands(SessionCommands.Builder().build())
                    .setAvailablePlayerCommands(PLAYBACK_COMMANDS)
            }
            return builder.build()
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> =
            if (isOwnController(mediaSession, controller)) {
                Futures.immediateFuture(mediaItems)
            } else {
                Futures.immediateFailedFuture(SecurityException("Controller may not change the queue"))
            }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            if (isOwnController(mediaSession, controller)) {
                Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
                )
            } else {
                Futures.immediateFailedFuture(SecurityException("Controller may not change the queue"))
            }

        // Restores only Ordo's own persisted queue, and only what still exists.
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val state = stateStore.read()
            if (state == null) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        emptyList(), C.INDEX_UNSET, C.TIME_UNSET
                    )
                )
            }
            return Futures.submit(Callable { restorableState(state) }, ioExecutor)
        }
    }

    companion object {
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L

        private val PLAYBACK_COMMANDS = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE
            )
            .build()

        fun mediaItem(pathUri: String): MediaItem {
            val title = try {
                Paths.get(URI.create(pathUri)).fileName?.toString()
            } catch (_: Exception) {
                Uri.parse(pathUri).lastPathSegment
            }.orEmpty()
            return MediaItem.Builder()
                .setMediaId(pathUri)
                .setUri(Uri.parse(pathUri))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setDisplayTitle(title)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        }
    }
}
