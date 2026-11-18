/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.media3.session.SessionCommands
import java8.nio.file.Paths
import me.zhanghai.android.files.viewer.audio.AudioPlayerActivity

@UnstableApi
class AudioPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var stateStore: PlaybackStateStore
    private lateinit var playHistory: PlayHistoryStore
    // The song last counted in the play history; a pause and resume of the same song is not a new play.
    private var lastCountedMediaId: String? = null
    private lateinit var soundEffects: SoundEffectController
    private val klang = KlangProcessors()
    private lateinit var audioFocusController: AudioFocusController
    private val handler = Handler(Looper.getMainLooper())
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var lastQueue: List<String>? = null
    // Index in the player's queue to index in the persisted queue, or -1 for items that are never persisted.
    private var persistedIndices = IntArray(0)
    private var soundSettings = AudioSettings()
    private var replayGain: ReplayGainInfo? = null
    private lateinit var loudness: LoudnessAnalysis
    // The item whose loudness is being measured, so a late result for an earlier song is dropped.
    private var measuring: String? = null
    private var measurement: java.util.concurrent.Future<*>? = null
    // Its own low-priority thread: a slow or hostile file never holds up playback, saving or restoring the queue.
    private val loudnessExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "ordo-loudness")
    }
    private var sleepDeadlineElapsedMs: Long? = null
    private var sleepSelectionMinutes = 0
    private var sleepAtEndOfTrack = false
    private var sleepVolume = 1f
    private var focusVolume = 1f
    private var transportVolume = 1f
    // Gentle play, pause and stop (TransportFader); a pulled-out headphone still pauses at once.
    private val fader = TransportFader(
        { SystemClock.uptimeMillis() }, { action, delay -> handler.postDelayed(action, delay) },
        { handler.removeCallbacks(it) }
    ) {
        transportVolume = it
        if (::player.isInitialized) updatePlayerVolume()
    }
    private var pausedForDisconnectedHeadphones = false
    private val savePosition = object : Runnable {
        override fun run() {
            persistPosition()
            if (::player.isInitialized && player.isPlaying) {
                handler.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
            }
        }
    }
    private val sleepTimer = object : Runnable {
        override fun run() {
            if (!::player.isInitialized) return
            val deadline = sleepDeadlineElapsedMs
            sleepVolume = when {
                deadline != null -> SleepTimerSchedule.volume(deadline, SystemClock.elapsedRealtime())
                sleepAtEndOfTrack -> SleepTimerSchedule.endOfTrackVolume(player.duration, player.currentPosition)
                else -> 1f
            }
            updatePlayerVolume()
            if (sleepVolume <= 0f) {
                player.pause()
                cancelSleepTimer()
            } else if (deadline != null || sleepAtEndOfTrack) {
                handler.postDelayed(this, SLEEP_UPDATE_INTERVAL_MS)
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && player.isPlaying) {
                pausedForDisconnectedHeadphones = true
                // At once, no fade: the speaker must not keep playing aloud for another 120 ms.
                fader.immediate { player.pause() }
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            // The headphone crossfeed follows the route: on with headphones, off on the speaker.
            klang.apply(soundSettings, headphonesConnected())
            if (!soundSettings.resumeOnHeadphones || !pausedForDisconnectedHeadphones) return
            if (addedDevices.any(::isHeadphoneDevice)) {
                pausedForDisconnectedHeadphones = false
                player.play()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            klang.apply(soundSettings, headphonesConnected())
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            // Media3 explicitly reports replacement AudioTrack sessions here; effects must follow the new ID.
            // https://developer.android.com/reference/androidx/media3/common/Player.Listener#onAudioSessionIdChanged(int)
            soundEffects.attach(audioSessionId)
        }

        // Paused (after its fade), stopped, ended or waiting after a seek: nothing is heard, the effects may switch.
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            soundEffects.setAudible(isPlaying)
        }

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
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) lastCountedMediaId = null
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED)) {
                countPlay()
            }
            if (events.contains(Player.EVENT_TRACKS_CHANGED)) {
                replayGain = ReplayGainMetadata.fromTracks(player.currentTracks)
                soundEffects.apply(soundSettings, replayGain)
                measureLoudnessIfUntagged()
            }
            if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
                if (player.playWhenReady) audioFocusController.requestForPlayback()
                else audioFocusController.onPlaybackPaused()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (sleepAtEndOfTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                player.pause()
                cancelSleepTimer()
            }
        }
    }

    /** A song that starts to play counts once in Ordo Music's history; `content://` songs of other apps never. */
    private fun countPlay() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (!player.isPlaying || mediaId == lastCountedMediaId || mediaId.startsWith("content:")) return
        lastCountedMediaId = mediaId
        val now = System.currentTimeMillis()
        ioExecutor.execute { playHistory.record(mediaId, now) }
    }

    override fun onCreate() {
        super.onCreate()
        stateStore = PlaybackStateStore(this)
        loudness = LoudnessAnalysis(this)
        playHistory = PlayHistoryStore(this)
        soundSettings = AudioSettingsStore.read()
        // A platform effect is only ever switched in silence, or inside a short dip while music plays.
        soundEffects = SoundEffectController(handler) { action ->
            if (::player.isInitialized && player.isPlaying) fader.duck(action) else fader.immediate(action)
        }
        klang.apply(soundSettings, headphonesConnected())
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val modes = stateStore.readModes()
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(AudioRenderersFactory(this, klang.chain))
            .setMediaSourceFactory(DefaultMediaSourceFactory(PathDataSource.Factory(this)))
            .setAudioAttributes(audioAttributes, false)
            // Ordo handles this broadcast itself so it can remember that headphones caused the pause.
            // https://developer.android.com/media/optimize/audio-focus#becoming-noisy
            .setHandleAudioBecomingNoisy(false)
            // Holds the Wi-Fi lock too, so SFTP/SMB/WebDAV/FTP playback survives the screen turning off.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                // The modes of the last session are restored here, not from a session callback.
                shuffleModeEnabled = modes.shuffleEnabled
                repeatMode = modes.repeatMode
                addListener(playerListener)
            }
        audioFocusController = AudioFocusController(this, player, handler) {
            focusVolume = it
            updatePlayerVolume()
        }.apply { setBehavior(soundSettings.focusBehavior) }
        applySoundSettings()
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_EXPORTED
        )
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(audioDeviceCallback, handler)
        // The system volume lands in Settings.System; its observer tells "full sound when quiet" the new level.
        contentResolver.registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, volumeObserver)
        soundEffects.setVolumeFraction(volumeFraction())
        // Play, pause and stop from the notification, the lock screen, headphone buttons or Ordo's own screen come
        // through here and fade instead of cutting the wave off mid-way (an audible crack). Seeks need nothing:
        // Media3 ramps the first 20 ms of every new AudioTrack itself.
        val sessionPlayer = object : ForwardingPlayer(player) {
            override fun play() = fader.fadeIn { player.play() }

            override fun pause() = if (player.isPlaying) fader.fadeOutThen { player.pause() }
            else fader.immediate { player.pause() }

            override fun stop() = if (player.isPlaying) fader.fadeOutThen { player.stop() }
            else fader.immediate { player.stop() }

            override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else pause()

            // The file name is only the display title until the file's own title tag is read; the system media
            // controls prefer the display title, so they showed "song.mp3" instead of the song's name.
            override fun getMediaMetadata(): MediaMetadata {
                val metadata = super.getMediaMetadata()
                val title = metadata.title?.takeIf { it.isNotBlank() } ?: return metadata
                return metadata.buildUpon().setDisplayTitle(title).build()
            }
        }
        mediaSession = MediaSession.Builder(this, sessionPlayer)
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
        handler.removeCallbacks(sleepTimer)
        runCatching { unregisterReceiver(noisyReceiver) }
        contentResolver.unregisterContentObserver(volumeObserver)
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(audioDeviceCallback)
        if (::audioFocusController.isInitialized) audioFocusController.abandon()
        if (::soundEffects.isInitialized) soundEffects.release()
        if (::player.isInitialized) {
            persistPosition()
            player.removeListener(playerListener)
        }
        if (::mediaSession.isInitialized) mediaSession.release()
        if (::player.isInitialized) player.release()
        ioExecutor.shutdown()
        loudnessExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun applySoundSettings() {
        soundSettings = AudioSettingsStore.read()
        klang.apply(soundSettings, headphonesConnected())
        audioFocusController.setBehavior(soundSettings.focusBehavior)
        // A pitch of 1 keeps pitch unchanged while Sonic time-stretches playback.
        // https://developer.android.com/reference/androidx/media3/common/PlaybackParameters
        player.playbackParameters = PlaybackParameters(soundSettings.speed, 1f)
        // https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer#setSkipSilenceEnabled(boolean)
        player.skipSilenceEnabled = soundSettings.skipSilence
        soundEffects.apply(soundSettings, replayGain)
        measureLoudnessIfUntagged()
    }

    /**
     * Volume levelling without tags: a local file is measured once after ITU-R BS.1770 in the background
     * ([LoudnessAnalysis], cached); the gain then glides in like any other change.
     */
    private fun measureLoudnessIfUntagged() {
        val uri = player.currentMediaItem?.localConfiguration?.uri
        val path = uri?.takeIf { it.scheme == ContentResolver.SCHEME_FILE }?.path
        // Another song (or levelling off): a running measurement for the previous one is cancelled.
        if (measuring != null && measuring != path) {
            measurement?.cancel(true)
            measuring = null
        }
        if (!soundSettings.normalizationEnabled || replayGain != null || path == null) return
        if (measuring == path) return
        val file = java.io.File(path)
        when (val cached = loudness.cached(file)) {
            is LoudnessAnalysis.Cached.Measured -> {
                replayGain = cached.info
                soundEffects.apply(soundSettings, replayGain)
                return
            }
            LoudnessAnalysis.Cached.Failed -> return
            null -> Unit
        }
        measuring = path
        measurement = loudnessExecutor.submit(Runnable {
            val result = loudness.measure(file)
            handler.post {
                if (measuring != path) return@post
                measuring = null
                if (result != null && player.currentMediaItem?.localConfiguration?.uri?.path == path &&
                    replayGain == null) {
                    replayGain = result
                    soundEffects.apply(soundSettings, replayGain)
                }
            }
        })
    }

    private fun updatePlayerVolume() {
        player.volume = (sleepVolume * focusVolume * transportVolume).coerceIn(0f, 1f)
    }

    private fun setSleepTimer(minutes: Int, endOfTrack: Boolean) {
        handler.removeCallbacks(sleepTimer)
        sleepDeadlineElapsedMs = minutes.takeIf { it > 0 }
            ?.let { SleepTimerSchedule.deadline(SystemClock.elapsedRealtime(), it) }
        sleepSelectionMinutes = minutes.takeIf { it > 0 } ?: 0
        sleepAtEndOfTrack = endOfTrack
        sleepVolume = 1f
        updatePlayerVolume()
        if (sleepDeadlineElapsedMs != null || sleepAtEndOfTrack) handler.post(sleepTimer)
    }

    private fun cancelSleepTimer() {
        handler.removeCallbacks(sleepTimer)
        sleepDeadlineElapsedMs = null
        sleepSelectionMinutes = 0
        sleepAtEndOfTrack = false
        sleepVolume = 1f
        updatePlayerVolume()
    }

    private val volumeObserver = object : android.database.ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (::soundEffects.isInitialized) soundEffects.setVolumeFraction(volumeFraction())
        }
    }

    private fun volumeFraction(): Float {
        val audioManager = getSystemService(AudioManager::class.java)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat() else 1f
    }

    private fun headphonesConnected(): Boolean =
        getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any(::isHeadphoneDevice)

    private fun isHeadphoneDevice(device: AudioDeviceInfo): Boolean = when (device.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_HEADSET -> true
        else -> isModernHeadphoneDevice(device)
    }

    private fun isModernHeadphoneDevice(device: AudioDeviceInfo): Boolean =
        (Build.VERSION.SDK_INT >= 28 && isHearingAid(device)) ||
            (Build.VERSION.SDK_INT >= 31 && isBleHeadset(device))

    @android.annotation.TargetApi(28)
    private fun isHearingAid(device: AudioDeviceInfo) = device.type == AudioDeviceInfo.TYPE_HEARING_AID

    @android.annotation.TargetApi(31)
    private fun isBleHeadset(device: AudioDeviceInfo) = device.type == AudioDeviceInfo.TYPE_BLE_HEADSET

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
        val restored = restorableState(state, ::exists)
        if (restored == null) {
            stateStore.clearQueue()
            return MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
        }
        if (restored.paths.size != state.paths.size) {
            stateStore.writeQueue(restored.paths)
            stateStore.writePosition(restored.currentIndex, restored.positionMs)
        }
        return MediaSession.MediaItemsWithStartPosition(
            restored.paths.map(::mediaItem), restored.currentIndex, restored.positionMs
        )
    }

    private fun exists(pathUri: String): Boolean = pathUriExists(pathUri)

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
            val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            if (isOwnController(session, controller)) {
                builder.setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(AudioPlayerCommands.APPLY_SETTINGS)
                        .add(AudioPlayerCommands.GET_SOUND_STATUS)
                        .add(AudioPlayerCommands.SLEEP_TIMER)
                        .build()
                )
            }
            if (!isOwnController(session, controller)) {
                builder
                    .setAvailableSessionCommands(SessionCommands.Builder().build())
                    .setAvailablePlayerCommands(PLAYBACK_COMMANDS)
            }
            return builder.build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (!isOwnController(session, controller)) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            return when (customCommand.customAction) {
                AudioPlayerCommands.ACTION_APPLY_SETTINGS -> {
                    applySoundSettings()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                AudioPlayerCommands.ACTION_GET_SOUND_STATUS -> {
                    val status = soundEffects.status
                    Futures.immediateFuture(
                        SessionResult(
                            SessionResult.RESULT_SUCCESS,
                            bundleOf(
                                AudioPlayerCommands.EXTRA_EQ_AVAILABLE to status.equalizerAvailable,
                                AudioPlayerCommands.EXTRA_DP to status.usingDynamicsProcessing,
                                AudioPlayerCommands.EXTRA_INPUT_GAIN_AVAILABLE to status.inputGainAvailable,
                                AudioPlayerCommands.EXTRA_LEGACY_BANDS to status.legacyBandCount,
                                AudioPlayerCommands.EXTRA_BASS_AVAILABLE to status.bassBoostAvailable,
                                AudioPlayerCommands.EXTRA_CLARITY_AVAILABLE to status.clarityAvailable,
                                AudioPlayerCommands.EXTRA_HEADPHONES to headphonesConnected(),
                                AudioPlayerCommands.EXTRA_HIGHS_CUTOFF to klang.highs.detectedCutoffHz,
                                AudioPlayerCommands.EXTRA_VOLUME_FRACTION to volumeFraction(),
                                AudioPlayerCommands.EXTRA_SLEEP_ACTIVE to
                                    (sleepDeadlineElapsedMs != null || sleepAtEndOfTrack),
                                AudioPlayerCommands.EXTRA_SLEEP_MINUTES to sleepSelectionMinutes,
                                AudioPlayerCommands.EXTRA_END_OF_TRACK to sleepAtEndOfTrack,
                                AudioPlayerCommands.EXTRA_SLEEP_REMAINING_MS to
                                    (sleepDeadlineElapsedMs?.let { it - SystemClock.elapsedRealtime() } ?: 0L)
                                        .coerceAtLeast(0L)
                            )
                        )
                    )
                }
                AudioPlayerCommands.ACTION_SLEEP_TIMER -> {
                    setSleepTimer(
                        args.getInt(AudioPlayerCommands.EXTRA_SLEEP_MINUTES),
                        args.getBoolean(AudioPlayerCommands.EXTRA_END_OF_TRACK)
                    )
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
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
        private const val SLEEP_UPDATE_INTERVAL_MS = 250L

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
