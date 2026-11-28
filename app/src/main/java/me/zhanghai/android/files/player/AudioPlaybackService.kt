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
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
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
import me.zhanghai.android.files.R
import me.zhanghai.android.files.player.cast.CastManager
import me.zhanghai.android.files.player.headphones.HeadphoneOutput
import me.zhanghai.android.files.player.output.HiResOutput
import me.zhanghai.android.files.player.output.OutputPlan
import me.zhanghai.android.files.player.output.OutputPolicy
import me.zhanghai.android.files.viewer.audio.AudioPlayerActivity
import me.zhanghai.android.files.viewer.audio.MusicCatalog
import me.zhanghai.android.files.viewer.audio.MusicFilter
import me.zhanghai.android.files.viewer.audio.MusicLibrary
import me.zhanghai.android.files.viewer.audio.MusicSelection
import me.zhanghai.android.files.viewer.audio.queryMusicBlocking
import me.zhanghai.android.files.viewer.video.sound.VideoSpatial

@UnstableApi
class AudioPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var stateStore: PlaybackStateStore
    // Opened on first use, which is on the I/O executor.
    private val playHistory: PlayHistoryStore by lazy { PlayHistoryStore(applicationContext) }
    // The song last counted in the play history; a pause and resume of the same song is not a new play.
    private var lastCountedMediaId: String? = null
    private lateinit var soundEffects: SoundEffectController
    private val klang = KlangProcessors()
    // Surround layouts the system's spatializer renders itself; Ordo's virtual surround leaves those alone.
    private var spatialChannels: Set<Int> = emptySet()
    private val spatial by lazy {
        VideoSpatial(this, handler) {
            spatialChannels = it
            applyKlang()
        }
    }
    private lateinit var hiRes: HiResOutput
    // The AudioTrack runs offloaded (decoded in the audio DSP); from Media3's onAudioTrackInitialized.
    private var offloaded = false
    private var outputLabel: String? = null
    private lateinit var audioFocusController: AudioFocusController
    private val handler = Handler(Looper.getMainLooper())
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var lastQueue: List<String>? = null
    // Index in the player's queue to index in the persisted queue, or -1 for items that are never persisted.
    private var persistedIndices = IntArray(0)
    private var soundSettings = AudioSettings()
    private var replayGain: ReplayGainInfo? = null
    private val loudness: LoudnessAnalysis by lazy { LoudnessAnalysis(applicationContext) }
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
            // The headphone crossfeed follows the route: on with headphones, off on the speaker; a USB DAC may
            // switch bit-perfect on.
            applySoundSettings()
            if (!soundSettings.resumeOnHeadphones || !pausedForDisconnectedHeadphones) return
            if (addedDevices.any(HeadphoneOutput::isHeadphones)) {
                pausedForDisconnectedHeadphones = false
                player.play()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            // Also gives the mixer back when the USB DAC is gone.
            applySoundSettings()
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
            // Ordo's video player may have set the mixer for a film meanwhile; take it back for this song.
            // Paused, Ordo Video gets a normal mixer.
            // Buffering after a seek keeps the mixer: the AudioTrack reopens with it (GE-7).
            if (isPlaying) hiRes.onConfigure(hiRes.track)
            else if (!player.playWhenReady || player.playbackState != Player.STATE_BUFFERING) hiRes.suspend()
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
                applyEffects()
                measureLoudnessIfUntagged()
            }
            if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
                if (player.playWhenReady) audioFocusController.requestForPlayback()
                else {
                    audioFocusController.onPlaybackPaused()
                    // Paused while buffering: isPlaying was already false, so onIsPlayingChanged did not suspend.
                    if (!events.contains(Player.EVENT_IS_PLAYING_CHANGED)) hiRes.suspend()
                }
            }
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
                player.playbackState == Player.STATE_ENDED && sleepAtEndOfTrack) {
                cancelSleepTimer()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The Wi-Fi lock only for files on a server: with the screen off it keeps Wi-Fi out of power saving.
            player.setWakeMode(
                if (mediaItem?.localConfiguration?.uri?.scheme == "file") C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK
            )
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
        // The application context already resolved its preferences folder; the service's own would check it on the
        // main thread again.
        stateStore = PlaybackStateStore(applicationContext)
        soundSettings = AudioSettingsStore.read()
        // A platform effect is only ever switched in silence, or inside a short dip while music plays.
        soundEffects = SoundEffectController(handler) { action ->
            if (::player.isInitialized && player.isPlaying) fader.duck(action) else fader.immediate(action)
        }
        applyKlang()
        hiRes = HiResOutput(this, allowsBitPerfect = true).apply {
            // The song went bit-perfect or not: its stages follow (the platform effects too).
            onChanged = {
                handler.post {
                    publishOutput()
                    if (::player.isInitialized) applySoundSettings()
                }
            }
        }
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val modes = stateStore.readModes()
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(AudioRenderersFactory(this, klang.chain, hiRes))
            .setMediaSourceFactory(DefaultMediaSourceFactory(PathDataSource.Factory(this)))
            .setAudioAttributes(audioAttributes, false)
            // Ordo handles this broadcast itself so it can remember that headphones caused the pause.
            // https://developer.android.com/media/optimize/audio-focus#becoming-noisy
            .setHandleAudioBecomingNoisy(false)
            // Holds the Wi-Fi lock too, so SFTP/SMB/WebDAV/FTP playback survives the screen turning off; local files
            // switch to WAKE_MODE_LOCAL in onMediaItemTransition.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                // The modes of the last session are restored here, not from a session callback.
                shuffleModeEnabled = modes.shuffleEnabled
                repeatMode = modes.repeatMode
                addListener(playerListener)
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onAudioTrackInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        audioTrackConfig: AudioSink.AudioTrackConfig
                    ) {
                        offloaded = audioTrackConfig.offload
                        publishOutput()
                    }

                    override fun onAudioTrackReleased(
                        eventTime: AnalyticsListener.EventTime,
                        audioTrackConfig: AudioSink.AudioTrackConfig
                    ) {
                        offloaded = false
                        publishOutput()
                    }
                })
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
        spatial.start()
        // Play, pause and stop from the notification, the lock screen, headphone buttons or Ordo's own screen come
        // through here and fade instead of cutting the wave off mid-way (an audible crack). Seeks need nothing:
        // Media3 ramps the first 20 ms of every new AudioTrack itself.
        val sessionPlayer = object : ForwardingPlayer(player) {
            override fun play() = fader.fadeIn { player.play() }

            override fun pause() {
                audioFocusController.onUserPause()
                if (player.isPlaying) fader.fadeOutThen { player.pause() }
                else fader.immediate { player.pause() }
            }

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
        CastManager.attach(this, mediaSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistPosition()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Ends casting first, so the session holds the local player again when it is released.
        CastManager.detach()
        if (::hiRes.isInitialized) hiRes.onChanged = null
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(noisyReceiver) }
        contentResolver.unregisterContentObserver(volumeObserver)
        spatial.stop()
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(audioDeviceCallback)
        if (::audioFocusController.isInitialized) audioFocusController.abandon()
        if (::soundEffects.isInitialized) soundEffects.release()
        if (::player.isInitialized) {
            persistPosition()
            player.removeListener(playerListener)
        }
        if (::hiRes.isInitialized) hiRes.release()
        if (::mediaSession.isInitialized) mediaSession.release()
        if (::player.isInitialized) player.release()
        ioExecutor.shutdown()
        loudnessExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun applySoundSettings() {
        val stored = AudioSettingsStore.read()
        hiRes.wantsBitPerfect = stored.bitPerfectUsb
        val rebuild = hiRes.refresh()
        // This song goes bit-perfect: Klang, effects, levelling, limiter gain, speed and silence skipping are bypassed.
        // A song the DAC cannot take unchanged plays normally, with its effects.
        soundSettings = if (hiRes.plan is OutputPlan.BitPerfect) OutputPolicy.bypassed(stored) else stored
        applyKlang()
        audioFocusController.setBehavior(soundSettings.focusBehavior)
        // A pitch of 1 keeps pitch unchanged while Sonic time-stretches playback.
        // https://developer.android.com/reference/androidx/media3/common/PlaybackParameters
        player.playbackParameters = PlaybackParameters(soundSettings.speed, 1f)
        // https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer#setSkipSilenceEnabled(boolean)
        player.skipSilenceEnabled = soundSettings.skipSilence
        applyEffects()
        measureLoudnessIfUntagged()
        applyOffload()
        if (rebuild) rebuildAudio()
    }

    /**
     * Offload saves battery but decodes past Ordo's stages, so only while none is on; gapless is required, so an
     * album never gets gaps from it.
     * https://developer.android.com/reference/androidx/media3/common/TrackSelectionParameters.AudioOffloadPreferences
     */
    private fun applyOffload() {
        val mode = if (OutputPolicy.offloadAllowed(soundSettings, hiRes.bitPerfectMode)) {
            AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
        } else AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        if (player.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode == mode) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(mode)
                    .setIsGaplessSupportRequired(true)
                    .build()
            )
            .build()
    }

    /**
     * Bit-perfect went on or off: decoders and sink are set up anew, so the decoder hands over float (hi-res, exact)
     * or 16-bit as the chosen sink needs. Position and play state stay.
     */
    private fun rebuildAudio() {
        if (player.playbackState == Player.STATE_IDLE || player.mediaItemCount == 0) return
        player.stop()
        player.prepare()
    }

    /** "Bit-perfekt · 24/96" or "Offload" for the player's badge, through the session extras. */
    private fun publishOutput() {
        if (!::mediaSession.isInitialized) return
        val plan = hiRes.plan
        val track = hiRes.track
        val label = when {
            plan is OutputPlan.BitPerfect && track != null -> getString(
                R.string.audio_output_bit_perfect_format,
                OutputPolicy.label(hiRes.source.bits?.takeUnless { hiRes.source.lossy }, track.sampleRate)
            )
            offloaded -> getString(R.string.audio_output_offload)
            else -> null
        }
        if (label == outputLabel) return
        outputLabel = label
        mediaSession.setSessionExtras(bundleOf(AudioPlayerCommands.EXTRA_OUTPUT to label))
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
        measuring = path
        // The cache lookup stats the file, so it runs here too, off the main thread.
        measurement = loudnessExecutor.submit(Runnable {
            val result = loudness.cachedOrMeasure(file)
            handler.post {
                if (measuring != path) return@post
                measuring = null
                if (result != null && player.currentMediaItem?.localConfiguration?.uri?.path == path &&
                    replayGain == null) {
                    replayGain = result
                    applyEffects()
                }
            }
        })
    }

    /** Platform effects and the chain's loudness gain, from the settings and this song's level. */
    private fun applyEffects() {
        soundEffects.apply(soundSettings, replayGain)
        klang.setLoudness(soundSettings, replayGain)
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
            // "Full sound when quiet" follows the volume.
            applyKlang()
        }
    }

    /** Ordo's chain for the current route and volume (headphones, loudness shelves, virtual surround). */
    private fun applyKlang() {
        val audioManager = getSystemService(AudioManager::class.java)
        klang.apply(
            soundSettings, headphonesConnected(), HeadphoneOutput.attenuationDb(audioManager), spatialChannels
        )
    }

    private fun headphonesConnected(): Boolean =
        HeadphoneOutput.headphones(getSystemService(AudioManager::class.java)) != null

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
                                AudioPlayerCommands.EXTRA_QUIET_BASS_DB to klang.headphones.quiet.let {
                                    if (it.effectEnabled) it.bassDb else 0f
                                },
                                AudioPlayerCommands.EXTRA_SONG_BIT_PERFECT to (hiRes.plan is OutputPlan.BitPerfect),
                                AudioPlayerCommands.EXTRA_SONG_NOT_BIT_PERFECT to
                                    (hiRes.bitPerfectMode && hiRes.track != null &&
                                        hiRes.plan !is OutputPlan.BitPerfect),
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
                val query = searchQuery(controller, mediaItems)
                    ?: return Futures.immediateFailedFuture(SecurityException("Controller may not change the queue"))
                Futures.submit(Callable { searchResults(query) }, ioExecutor)
            }

        /**
         * "Play X" from the Assistant or another controller arrives as one item with only a search query (Media3's
         * playFromSearch). Ordo answers with its own files; a URI from outside is never opened. Only trusted
         * controllers (system apps such as the Assistant) may search, so no app can list the library by guessing.
         */
        private fun searchQuery(controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): String? =
            mediaItems.singleOrNull()?.takeIf { controller.isTrusted && it.localConfiguration == null }
                ?.requestMetadata?.searchQuery

        // Nothing found fails the request, so the caller says so instead of silence.
        private fun searchResults(query: String): List<MediaItem> {
            val songs = MusicLibrary(
                queryMusicBlocking(this@AudioPlaybackService), false, java.text.Collator.getInstance()
            ).songs
            // "Play music" without a name: everything, shuffled.
            val found = if (query.isBlank()) MusicLibrary.shuffled(songs)
            else MusicSelection.select(MusicCatalog(songs), MusicFilter(query = query))
            if (found.isEmpty()) throw NoSuchElementException("No song matches the search")
            return found.slice(queueWindow(found.size, 0)).map { mediaItem(Paths.get(it.path).toUri().toString()) }
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
                val query = searchQuery(controller, mediaItems)
                    ?: return Futures.immediateFailedFuture(SecurityException("Controller may not change the queue"))
                Futures.submit(Callable {
                    MediaSession.MediaItemsWithStartPosition(searchResults(query), 0, 0L)
                }, ioExecutor)
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
                Player.COMMAND_GET_TIMELINE,
                // Only for "play X": the session callback turns away every item that is not a bare search.
                Player.COMMAND_SET_MEDIA_ITEM
            )
            .build()

        /** [knownTitle] skips looking the name up (a query for a content:// item: not on the main thread). */
        fun mediaItem(pathUri: String, knownTitle: String? = null): MediaItem {
            val title = knownTitle ?: try {
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
