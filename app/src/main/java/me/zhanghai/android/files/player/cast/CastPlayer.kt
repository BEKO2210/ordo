/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.cast

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.PlayerTransferState
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * The media session's player while casting. Transport, seeking, queue and the device volume go to the receiver;
 * what the receiver reports comes back as player state, so the notification, the lock screen, the volume keys
 * (PLAYBACK_TYPE_REMOTE) and Ordo's own player screen all show and control the TV.
 */
@UnstableApi
class CastPlayer(
    private val client: CastClient,
    /** Blocking: serves the item's file and reads its tags. Runs on a background thread. */
    private val prepareTrack: (MediaItem) -> Prepared
) : SimpleBasePlayer(Looper.getMainLooper()) {
    class Prepared(val track: CastProtocol.Track, val metadata: MediaMetadata)

    private class Entry(val item: MediaItem, var metadata: MediaMetadata) {
        val uid = Any()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "cast-prepare") }
    /** Play order: what Media3 sees, so Next and the end of a song follow it, shuffled or not. */
    private var entries = mutableListOf<Entry>()
    /** The queue as the user built it; equals [entries] unless shuffle is on. */
    private var original = mutableListOf<Entry>()
    private var index = 0
    private var wantsPlay = false
    private var castState = Player.STATE_IDLE
    private var status: CastProtocol.MediaStatus? = null
    private var statusAtMs = 0L
    private var pendingPositionMs = 0L
    private var durationMs = C.TIME_UNSET
    private var repeat = Player.REPEAT_MODE_OFF
    private var shuffle = false
    private var volumeSteps = 0
    private var deviceMuted = false
    private var error: PlaybackException? = null
    private var loadGeneration = 0
    private var awaitingRequestId = NO_REQUEST
    private var previousMediaSessionId: Int? = null

    /** Takes over queue, position and modes from the local player; [current] has the tags it already read. */
    fun start(
        state: PlayerTransferState,
        current: MediaMetadata,
        volumeLevel: Double?,
        muted: Boolean?,
        /** The phone's shuffle order (queue indices in play order), so the TV plays the same next song. */
        shuffleOrder: IntArray?
    ) {
        original = state.mediaItems.mapTo(mutableListOf()) { Entry(it, it.mediaMetadata) }
        val currentEntry = original.getOrNull(state.currentMediaItemIndex.coerceIn(0, (original.size - 1).coerceAtLeast(0)))
        currentEntry?.metadata = current
        repeat = state.repeatMode
        shuffle = state.shuffleModeEnabled
        val order = shuffleOrder?.takeIf { shuffle && CastPlayback.isPermutation(it, original.size) }
        entries = order?.mapTo(mutableListOf()) { original[it] } ?: original.toMutableList()
        index = entries.indexOf(currentEntry).coerceAtLeast(0)
        volumeLevel?.let { volumeSteps = CastPlayback.deviceVolume(it) }
        muted?.let { deviceMuted = it }
        wantsPlay = state.playWhenReady
        if (entries.isNotEmpty()) load(index, state.currentPosition.coerceAtLeast(0L), wantsPlay)
        invalidateState()
    }

    /**
     * What the phone takes back: the queue in the user's order, the current song and position, the modes, and the
     * shuffle order in queue indices (null without shuffle). Paused, so the phone does not start playing aloud.
     */
    fun handBack(): Pair<PlayerTransferState, IntArray?> {
        val currentEntry = entries.getOrNull(index)
        val state = PlayerTransferState.Builder()
            .setMediaItems(original.map { it.item })
            .setCurrentMediaItemIndex(original.indexOf(currentEntry).coerceAtLeast(0))
            .setCurrentPosition(positionMs())
            .setPlayWhenReady(false)
            .setRepeatMode(repeat)
            .setShuffleModeEnabled(shuffle)
            .build()
        val order = if (shuffle) entries.map { original.indexOf(it) }.toIntArray() else null
        return state to order
    }

    // Called on the cast reader thread.
    fun onMediaStatus(newStatus: CastProtocol.MediaStatus) = handler.post {
        if (awaitingRequestId != NO_REQUEST) {
            // Until our LOAD is answered, statuses still belong to the track before (INTERRUPTED and the like).
            // A Chromecast with Google TV first answers LOAD with a bare IDLE, then BUFFERING (measured 17.11.2026).
            val bareIdle = newStatus.playerState == "IDLE" && newStatus.idleReason == null
            val answersLoad = !bareIdle && (newStatus.requestId == awaitingRequestId ||
                (newStatus.mediaSessionId != previousMediaSessionId && newStatus.playerState != "IDLE"))
            if (!answersLoad) return@post
            awaitingRequestId = NO_REQUEST
        }
        status = newStatus
        statusAtMs = SystemClock.elapsedRealtime()
        newStatus.durationSeconds?.let { durationMs = (it * 1000).toLong() }
        wantsPlay = CastPlayback.playWhenReady(newStatus, wantsPlay)
        castState = CastPlayback.playbackState(newStatus)
        if (CastPlayback.isError(newStatus)) {
            error = PlaybackException(
                "The receiver could not play this file", null, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
            )
            wantsPlay = false
        }
        if (castState == Player.STATE_ENDED) {
            val next = nextMediaItemIndex
            if (next != C.INDEX_UNSET) load(next, 0L, true) else wantsPlay = false
        }
        invalidateState()
    }

    fun onReceiverStatus(receiver: CastProtocol.ReceiverStatus) = handler.post {
        receiver.volumeLevel?.let { volumeSteps = CastPlayback.deviceVolume(it) }
        receiver.muted?.let { deviceMuted = it }
        invalidateState()
    }

    fun onRequestFailed(requestId: Int) = handler.post {
        if (requestId != awaitingRequestId) return@post
        awaitingRequestId = NO_REQUEST
        error = PlaybackException(
            "The receiver refused this file", null, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        )
        castState = Player.STATE_IDLE
        wantsPlay = false
        invalidateState()
    }

    private fun positionMs(): Long {
        val current = status
        return if (current == null || awaitingRequestId != NO_REQUEST) {
            pendingPositionMs
        } else {
            CastPlayback.positionMs(current, statusAtMs, SystemClock.elapsedRealtime())
        }
    }

    private fun load(newIndex: Int, positionMs: Long, autoplay: Boolean) {
        val entry = entries.getOrNull(newIndex) ?: return
        index = newIndex
        pendingPositionMs = positionMs
        previousMediaSessionId = client.mediaSessionId
        status = null
        durationMs = C.TIME_UNSET
        error = null
        castState = Player.STATE_BUFFERING
        wantsPlay = autoplay
        val generation = ++loadGeneration
        awaitingRequestId = PREPARING
        io.execute {
            val prepared = runCatching { prepareTrack(entry.item) }
            handler.post {
                if (generation != loadGeneration) return@post
                prepared.onSuccess {
                    entry.metadata = it.metadata
                    // A pause while the file was being prepared still counts.
                    awaitingRequestId = client.load(it.track, positionMs / 1000.0, wantsPlay)
                }.onFailure {
                    awaitingRequestId = NO_REQUEST
                    error = PlaybackException("Could not open the file", it, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
                    castState = Player.STATE_IDLE
                    wantsPlay = false
                }
                invalidateState()
            }
        }
    }

    override fun getState(): State {
        val position = positionMs()
        val playing = wantsPlay && castState == Player.STATE_READY
        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(wantsPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (entries.isEmpty()) Player.STATE_IDLE else castState)
            .setPlayerError(error)
            .setRepeatMode(repeat)
            .setShuffleModeEnabled(shuffle)
            .setDeviceInfo(DEVICE_INFO)
            .setDeviceVolume(volumeSteps)
            .setIsDeviceMuted(deviceMuted)
            .setPlaylist(entries.mapIndexed { i, entry ->
                MediaItemData.Builder(entry.uid)
                    .setMediaItem(entry.item)
                    .setMediaMetadata(entry.metadata)
                    .setDurationUs(if (i == index && durationMs != C.TIME_UNSET) durationMs * 1000 else C.TIME_UNSET)
                    .setIsSeekable(true)
                    .build()
            })
            .setCurrentMediaItemIndex(if (entries.isEmpty()) C.INDEX_UNSET else index)
            .setContentPositionMs(
                if (playing) PositionSupplier.getExtrapolating(position, 1f) else PositionSupplier.getConstant(position)
            )
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        wantsPlay = playWhenReady
        when {
            !playWhenReady -> client.pause()
            castState == Player.STATE_ENDED -> load(index, 0L, true)
            castState == Player.STATE_IDLE -> load(index, positionMs(), true)
            else -> client.play()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (castState == Player.STATE_IDLE && entries.isNotEmpty()) load(index, positionMs(), wantsPlay)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        loadGeneration++
        awaitingRequestId = NO_REQUEST
        pendingPositionMs = positionMs()
        status = null
        castState = Player.STATE_IDLE
        client.stopMedia()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (mediaItemIndex == C.INDEX_UNSET || mediaItemIndex !in entries.indices) return Futures.immediateVoidFuture()
        val position = if (positionMs == C.TIME_UNSET) 0L else positionMs
        val current = status
        if (mediaItemIndex == index && current != null && awaitingRequestId == NO_REQUEST &&
            castState != Player.STATE_IDLE && castState != Player.STATE_ENDED) {
            client.seek(position / 1000.0)
            status = current.copy(currentTimeSeconds = position / 1000.0)
            statusAtMs = SystemClock.elapsedRealtime()
        } else {
            load(mediaItemIndex, position, wantsPlay)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        original = mediaItems.mapTo(mutableListOf()) { Entry(it, it.mediaMetadata) }
        var start = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (original.size - 1).coerceAtLeast(0))
        if (shuffle && original.isNotEmpty()) {
            entries = CastPlayback.shuffledStartingWith(original.size, start, Random.Default)
                .mapTo(mutableListOf()) { original[it] }
            start = 0
        } else {
            entries = original.toMutableList()
        }
        val position = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs
        if (entries.isEmpty()) {
            handleStop()
        } else if (castState != Player.STATE_IDLE) {
            load(start, position, wantsPlay)
        } else {
            index = start
            pendingPositionMs = position
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        val wasEmpty = entries.isEmpty()
        val added = mediaItems.map { Entry(it, it.mediaMetadata) }
        entries.addAll(index, added)
        if (shuffle) original.addAll(added) else original = entries.toMutableList()
        if (!wasEmpty && index <= this.index) this.index += mediaItems.size
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val removesCurrent = index in fromIndex until toIndex
        val removed = entries.subList(fromIndex, toIndex)
        original.removeAll(removed.toSet())
        removed.clear()
        when {
            entries.isEmpty() -> handleStop().also { index = 0 }
            removesCurrent -> load(fromIndex.coerceAtMost(entries.size - 1), 0L, wantsPlay)
            index >= toIndex -> index -= toIndex - fromIndex
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val current = entries.getOrNull(index)
        Util.moveItems(entries, fromIndex, toIndex, newIndex)
        if (!shuffle) original = entries.toMutableList()
        if (current != null) index = entries.indexOf(current)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        repeat = repeatMode
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (shuffle == shuffleModeEnabled) return Futures.immediateVoidFuture()
        shuffle = shuffleModeEnabled
        val current = entries.getOrNull(index)
        entries = if (shuffle && original.isNotEmpty()) {
            CastPlayback.shuffledStartingWith(original.size, original.indexOf(current).coerceAtLeast(0), Random.Default)
                .mapTo(mutableListOf()) { original[it] }
        } else {
            original.toMutableList()
        }
        if (current != null) index = entries.indexOf(current)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        volumeSteps = deviceVolume.coerceIn(0, CastPlayback.VOLUME_STEPS)
        client.setVolume(level = CastPlayback.level(volumeSteps))
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> = handleSetDeviceVolume(volumeSteps + 1, flags)

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> = handleSetDeviceVolume(volumeSteps - 1, flags)

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        deviceMuted = muted
        client.setVolume(muted = muted)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        loadGeneration++
        io.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        return Futures.immediateVoidFuture()
    }

    companion object {
        private const val NO_REQUEST = -1
        private const val PREPARING = -2

        private val DEVICE_INFO = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            .setMinVolume(0)
            .setMaxVolume(CastPlayback.VOLUME_STEPS)
            .build()

        private val COMMANDS = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_GET_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_ADJUST_DEVICE_VOLUME,
                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_RELEASE
            )
            .build()
    }
}
