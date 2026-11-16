/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import android.app.PendingIntent
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
import java8.nio.file.Paths
import me.zhanghai.android.files.viewer.audio.AudioPlayerActivity

@UnstableApi
class AudioPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var stateStore: PlaybackStateStore
    private val handler = Handler(Looper.getMainLooper())
    private val savePosition = object : Runnable {
        override fun run() {
            persistState()
            if (::player.isInitialized && player.isPlaying) {
                handler.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED
                )) {
                persistState()
            }
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                handler.removeCallbacks(savePosition)
                persistState()
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
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(PathDataSource.Factory(this)))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply {
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
        persistState()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(savePosition)
        if (::player.isInitialized) {
            persistState()
            player.removeListener(playerListener)
        }
        if (::mediaSession.isInitialized) mediaSession.release()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private fun persistState() {
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        stateStore.write(
            PlaybackStateStore.State(
                paths = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId },
                currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
                positionMs = player.currentPosition.coerceAtLeast(0L),
                shuffleEnabled = player.shuffleModeEnabled,
                repeatMode = player.repeatMode
            )
        )
    }

    private inner class SessionCallback : MediaSession.Callback {
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
            player.shuffleModeEnabled = state.shuffleEnabled
            player.repeatMode = state.repeatMode
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    state.paths.map(::mediaItem), state.currentIndex, state.positionMs
                )
            )
        }
    }

    companion object {
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L

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
