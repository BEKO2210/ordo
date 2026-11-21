/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.sound

import android.content.Context
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import java8.nio.file.Paths
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.player.AudioRenderersFactory
import me.zhanghai.android.files.player.output.HiResOutput
import me.zhanghai.android.files.player.KlangAudioProcessor
import me.zhanghai.android.files.player.LoudnessAnalysis
import me.zhanghai.android.files.player.LoudnessMeter
import me.zhanghai.android.files.player.LoudnessTargets
import me.zhanghai.android.files.player.MonoStage
import me.zhanghai.android.files.player.ReplayGainInfo
import me.zhanghai.android.files.player.SoundEffectController
import me.zhanghai.android.files.player.TransportFader
import me.zhanghai.android.files.player.TruePeakLimiter
import me.zhanghai.android.files.player.headphones.HeadphoneOutput
import me.zhanghai.android.files.player.headphones.HeadphoneStages
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import java.io.File
import java.net.URI
import java.util.concurrent.Executors

/**
 * The video player's sound, built from Ordo Musik's Klang engine: [SoundEffectController] (equalizer, bass, balance
 * and loudness levelling on the platform's DynamicsProcessing, switched only in silence or inside a short dip) and a
 * [KlangAudioProcessor] chain with mono, the dialog enhancer and night mode. Before it, [DownmixProcessor] folds
 * 5.1/7.1 to stereo with the centre kept audible (unless the system's spatializer takes the channels, [VideoSpatial]);
 * after it the [TruePeakLimiter] (−1 dBTP) and [AvDelayProcessor] (A/V sync, remembered per output device). The headphone
 * pack ([HeadphoneStages], virtual surround in the downmix) is shared with Ordo Musik.
 *
 * [settings], [update] and [setDelay] are the public way in, for the sheets today and for anything that wants to set
 * the sound later (no AI in the player itself). [onChanged] tells a visible sheet that something it shows changed.
 */
class VideoSoundEngine(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    // Small, quick jobs (storing a device's delay); the loudness of a whole film has its own thread and never holds them up.
    private val worker = Executors.newSingleThreadExecutor()
    private val loudnessWorker = Executors.newSingleThreadExecutor()
    private val mono = MonoStage()
    private val dialog = DialogStage()
    private val night = NightStage()
    private val headphones = HeadphoneStages()
    private val klang = KlangAudioProcessor(arrayOf(mono, dialog, night, headphones.quiet, headphones.correction))
    private val audioManager = context.getSystemService(AudioManager::class.java)
    // Headphones in or out: virtual surround and the headphone correction follow the route.
    private val routeCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onRoute()
    }
    // The system volume lands in Settings.System; "full sound when quiet" follows it.
    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = applyToChain()
    }
    private val downmix = DownmixProcessor()
    private val truePeak = TruePeakLimiter().apply { armed = true }
    private val delay = AvDelayProcessor()
    // What the system's spatializer could take; used only while none of Ordo's own stages needs the channels.
    private var spatialChannels: Set<Int> = emptySet()
    private val spatial = VideoSpatial(context, handler) { channels ->
        spatialChannels = channels
        applyPassThrough()
        notifyChanged()
    }
    private val delayStore = VideoDelayStore(context)
    private val deviceDelay = VideoDeviceDelay(delayStore::read) { key, value -> worker.execute { delayStore.write(key, value) } }
    // A film's level is measured once and cached (BS.1770, shared cache with Ordo Musik); a film takes longer.
    private val loudness by lazy { LoudnessAnalysis(context, LOUDNESS_MAX_MILLIS) }
    private var player: ExoPlayer? = null
    private val fader = TransportFader(
        { SystemClock.uptimeMillis() }, { action, delayMs -> handler.postDelayed(action, delayMs) },
        { handler.removeCallbacks(it) }
    ) { volumeMix.fader = it }

    /**
     * The player's volume as fader × sleep timer. The sleep timer sets [VolumeMix.sleep] (not player.volume), the effect
     * fader its own factor; neither overrides the other.
     */
    val volumeMix = VolumeMix { player?.volume = it }
    private val effects = SoundEffectController(handler) { action ->
        if (player?.isPlaying == true) fader.duck(action) else fader.immediate(action)
    }
    private var replayGain: ReplayGainInfo? = null
    /** Levels measured while this player is open, also those that "Don't remember places" keeps out of the cache. */
    private val sessionLoudness = HashMap<String, ReplayGainInfo?>()
    private var measuringId: String? = null
    private var measurement: java.util.concurrent.Future<*>? = null
    private var released = false
    private val listener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            effects.attach(audioSessionId)
            // What the device offers (bass, balance) is known only now.
            notifyChanged()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            effects.setAudible(isPlaying)
            // Music may have set the mixer meanwhile; the film's rate counts again. Paused, Ordo Musik gets a normal
            // mixer, as music hands it back when paused.
            if (isPlaying) output.onConfigure(output.track) else output.suspend()
            if (isPlaying) applyDeviceDelay()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = onItem(mediaItem)

        override fun onTracksChanged(tracks: Tracks) {
            sourceChannelCount = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                ?.let { group -> (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) }
                ?.channelCount ?: Format.NO_VALUE
            if (sourceChannelCount > 2) {
                val way = if (systemSpatializes(sourceChannelCount)) "system spatializer, passed through untouched"
                else "Ordo's BS.775 downmix to stereo"
                Log.i(TAG, "$sourceChannelCount channels: $way")
            }
            applyToChain()
            notifyChanged()
        }
    }

    var settings: VideoSoundSettings = VideoSoundStore.read(context)
        private set

    /** Sound later (+) or earlier (−) than the picture, in ms, for the current output device. */
    var delayMs: Int = 0
        private set

    /** The output the delay belongs to, for "Applies to: …"; null while unknown. */
    var delayDevice: AudioDeviceInfo? = null
        private set

    /** Channels of the selected sound track, or -1 while unknown. */
    var sourceChannelCount: Int = Format.NO_VALUE
        private set

    /** Called on the main thread when something a sheet shows has changed (effects, tracks, measured level). */
    var onChanged: (() -> Unit)? = null

    /** What the device's effects allow (bass, balance), for the sheet. */
    val status: SoundEffectController.Status get() = effects.status

    /**
     * Dialog enhancer, night mode and mono work on Ordo's stereo chain: while one of them is on, a surround track is
     * mixed by Ordo even where the system could spatialize it (otherwise they would silently do nothing).
     */
    val spatialHeldBack: Boolean
        get() = sourceChannelCount > 2 && sourceChannelCount in spatialChannels && needsOrdoChain()

    private fun needsOrdoChain(): Boolean =
        settings.dialogLevel != VideoSoundMath.DIALOG_OFF || settings.nightMode || settings.klang.mono

    /**
     * Sets which channel counts pass to the system. The downmix reads it when a format is configured; if the playing
     * track is affected, its audio is switched off and on again once, so the change is heard now (a short gap).
     */
    private fun applyPassThrough() {
        val wanted = if (needsOrdoChain()) emptySet() else spatialChannels
        val before = downmix.passThroughChannels
        if (wanted == before) return
        downmix.passThroughChannels = wanted
        val channels = sourceChannelCount
        if (channels > 2 && (channels in wanted) != (channels in before)) reconfigureAudio()
    }

    private fun reconfigureAudio() {
        val player = player ?: return
        val parameters = player.trackSelectionParameters
        if (C.TRACK_TYPE_AUDIO in parameters.disabledTrackTypes) return
        player.trackSelectionParameters = parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        handler.post {
            val current = this.player ?: return@post
            current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build()
        }
    }

    /** Whether a track of [channels] goes to the system's spatializer untouched instead of Ordo's downmix. */
    fun systemSpatializes(channels: Int): Boolean = channels > 2 && downmix.passingThrough

    /** Whether headphones are the output right now. */
    val headphonesConnected: Boolean get() = HeadphoneOutput.headphones(audioManager) != null

    /** Ordo's virtual surround renders this track right now (5.1/7.1, headphones, not the system's spatializer). */
    val virtualSurround: Boolean
        get() = sourceChannelCount > 2 && !downmix.passingThrough && downmix.binaural

    /** Levelling is on and this film's level is known (measured or cached). */
    val levelled: Boolean get() = settings.klang.normalizationEnabled && replayGain != null

    /**
     * The chain's active stages in plain words, in signal order, for the picture's "Signal path" sheet (#104). The
     * true-peak limiter shows only through the loudness line (it applies the target); it is always armed.
     */
    fun signalStages(): List<String> {
        val value = settings
        val klang = value.klang
        val number = java.text.NumberFormat.getInstance().apply { maximumFractionDigits = 1; minimumFractionDigits = 1 }
        val channels = sourceChannelCount
        // The same lines as the Klang sheet: spatializer or Ordo's mix, dialog distance, film and target loudness.
        val layout = if (channels > 2) {
            VideoSoundMath.channelLayout(channels) ?: context.getString(R.string.video_audio_track_channels, channels)
        } else null
        val reached = dialogReachedLu
        val measured = dialogDistanceLu
        val filmLufs = filmLufs
        return listOfNotNull(
            layout?.let {
                context.getString(
                    when {
                        systemSpatializes(channels) -> R.string.video_sound_spatial_format
                        virtualSurround -> R.string.headphones_virtual_surround_format
                        else -> R.string.video_sound_surround_format
                    },
                    it
                )
            },
            context.getString(R.string.audio_sound_mono).takeIf { klang.mono },
            if (value.dialogLevel == VideoSoundMath.DIALOG_OFF) null
            else if (reached.isNaN() || measured.isNaN()) context.getString(R.string.video_sound_dialog)
            else context.getString(
                R.string.video_sound_dialog_state, number.format(reached.toDouble()), number.format(measured.toDouble())
            ),
            context.getString(R.string.video_sound_night).takeIf { value.nightMode },
            context.getString(R.string.audio_sound_quiet).takeIf { headphones.quiet.effectEnabled },
            headphones.correction.profile?.takeIf { headphones.correction.effectEnabled }
                ?.let { context.getString(R.string.headphones_correction_state, it.name) },
            context.getString(R.string.audio_sound_equalizer).takeIf { klang.equalizerEnabled },
            context.getString(R.string.audio_sound_bass_boost).takeIf { klang.bassBoostEnabled },
            context.getString(R.string.audio_sound_balance).takeIf { klang.balance != 0 },
            if (!klang.normalizationEnabled) null
            else if (filmLufs == null) context.getString(R.string.video_sound_level)
            else context.getString(
                R.string.video_sound_level_measured, number.format(klang.loudnessTarget.lufs.toDouble()),
                number.format(filmLufs.toDouble())
            ),
            when {
                delayMs > 0 -> context.getString(R.string.video_audio_sync_later, delayMs)
                delayMs < 0 -> context.getString(R.string.video_audio_sync_earlier, -delayMs)
                else -> null
            }
        )
    }

    /** This film's integrated loudness in LUFS once measured (BS.1770), else null; for the sheet and signal path. */
    val filmLufs: Float? get() = replayGain?.let { LoudnessMeter.REFERENCE_LUFS.toFloat() - it.gainDb }

    /** Dialog-over-rest distance in LU: measured before and reached after the enhancer; NaN while unknown. */
    val dialogDistanceLu: Float get() = if (centreSteered()) downmix.distanceLu else dialog.distanceLu
    val dialogReachedLu: Float get() = if (centreSteered()) downmix.reachedLu else dialog.reachedLu

    /** 5.0-7.1 with a centre channel: the downmix steers it; stereo, mono and quad (no centre) use the heuristic. */
    private fun centreSteered(): Boolean = sourceChannelCount > 2 && sourceChannelCount != 4

    /**
     * "Besser verstehen" (Ward, Shirley 2019: speech-to-background distance, plus redundancy): dialog at 15 LU and
     * night mode in one tap; [onUnderstandBetter] lets the subtitle feature switch subtitles on with it.
     */
    fun setUnderstandBetter(on: Boolean) {
        update(
            settings.copy(
                dialogLevel = if (on) VideoSoundMath.DIALOG_STRONG else VideoSoundMath.DIALOG_OFF,
                nightMode = on
            )
        )
        onUnderstandBetter?.invoke(on)
    }

    val understandBetter: Boolean
        get() = settings.dialogLevel == VideoSoundMath.DIALOG_STRONG && settings.nightMode

    /** Hook for the subtitle feature: called with true when "Besser verstehen" goes on, false when off. */
    var onUnderstandBetter: ((Boolean) -> Unit)? = null

    /** The signal path's measurements: each Klang buffer's processing time (audio thread); null = off. */
    fun setMeter(meter: ((Long) -> Unit)?) {
        klang.meter = meter
    }

    // The mixer runs at the film's sample rate where the output allows (Android 14+); never bit-perfect, since the
    // film's sound always passes downmix, lip-sync delay and limiter.
    private val output = HiResOutput(context, allowsBitPerfect = false)

    fun renderersFactory(): AudioRenderersFactory =
        AudioRenderersFactory(context, arrayOf(downmix, klang, truePeak, delay), output)

    fun attach(player: ExoPlayer) {
        this.player = player
        player.addListener(listener)
        spatial.start()
        audioManager?.registerAudioDeviceCallback(routeCallback, handler)
        context.contentResolver.registerContentObserver(
            android.provider.Settings.System.CONTENT_URI, true, volumeObserver
        )
        applyToChain()
        effects.attach(player.audioSessionId)
        effects.setAudible(player.isPlaying)
        applyDeviceDelay()
        onItem(player.currentMediaItem)
    }

    fun update(value: VideoSoundSettings) {
        if (released) return
        val wasLevelling = settings.klang.normalizationEnabled
        settings = value.copy(klang = value.klang.sanitized())
        VideoSoundStore.write(context, settings)
        applyToChain()
        if (settings.klang.normalizationEnabled && !wasLevelling) measureLoudness(player?.currentMediaItem)
    }

    /** A new delay takes effect with a flush: a seek to where the film is (one short re-buffer). Kept per device. */
    fun setDelay(value: Int) {
        if (released) return
        applyDeviceDelay()
        deviceDelay.set(VideoSoundMath.stepDelay(value, 0))
        applyDelay(deviceDelay.delayMs)
    }

    /** The output may have changed: its own delay applies (0 for one never set). */
    private fun applyDeviceDelay() {
        val device = audioManager?.let(VideoDeviceDelay::output)
        delayDevice = device
        if (deviceDelay.route(device?.let(VideoDeviceDelay::key))) applyDelay(deviceDelay.delayMs)
    }

    private fun applyDelay(value: Int) {
        if (value == delayMs) return
        delayMs = value
        delay.delayMs = value
        player?.let { it.seekTo(it.currentPosition) }
    }

    /** A new film: its own level; the delay stays the output's. */
    private fun onItem(item: MediaItem?) {
        // The dialog distance belongs to the film: the next one starts measuring from nothing.
        dialog.steering.resetRequested = true
        downmix.steering.resetRequested = true
        replayGain = null
        applyToChain()
        if (item?.mediaId != null) measureLoudness(item)
    }

    private fun measureLoudness(item: MediaItem?) {
        if (!settings.klang.normalizationEnabled) return
        val mediaId = item?.mediaId ?: return
        if (measuringId == mediaId) return
        val policy = loudnessPolicy(mediaId, Settings.VIDEO_NO_HISTORY.valueCompat)
        if (policy == LoudnessPolicy.SKIP) return
        if (mediaId in sessionLoudness) {
            replayGain = sessionLoudness[mediaId]
            applyToChain()
            return
        }
        val file = localFile(mediaId) ?: return
        // Another film: the measurement of the previous one stops (LoudnessAnalysis checks the interrupt).
        measurement?.cancel(true)
        measuringId = mediaId
        val remember = policy == LoudnessPolicy.CACHE
        measurement = loudnessWorker.submit(Runnable {
            // Without history even the cached value of an earlier session is not looked up again by path.
            val cached = if (remember) loudness.cached(file) else null
            val result = when (cached) {
                is LoudnessAnalysis.Cached.Measured -> cached.info
                LoudnessAnalysis.Cached.Failed -> null
                null -> loudness.measure(file, remember)
            }
            // Cancelled for the next film: nothing was learnt about this one.
            if (Thread.currentThread().isInterrupted) return@Runnable
            if (BuildConfig.DEBUG) Log.d(TAG, "loudness: ${result?.gainDb ?: "not measurable"} dB to -18 LUFS")
            handler.post {
                if (measuringId == mediaId) measuringId = null
                if (released) return@post
                sessionLoudness[mediaId] = result
                if (player?.currentMediaItem?.mediaId != mediaId) return@post
                replayGain = result
                applyToChain()
                notifyChanged()
            }
        })
    }

    private fun applyToChain() {
        val value = settings
        mono.effectEnabled = value.klang.mono
        if (value.dialogLevel != VideoSoundMath.DIALOG_OFF) dialog.level = value.dialogLevel
        // 5.1/7.1: the downmix steers the real centre; the stereo heuristic only for stereo (and mono) tracks.
        dialog.effectEnabled = value.dialogLevel != VideoSoundMath.DIALOG_OFF && !centreSteered()
        downmix.dialogTargetLu = VideoSoundMath.dialogTargetLu(value.dialogLevel) ?: Float.NaN
        night.effectEnabled = value.nightMode
        val phones = audioManager?.let(HeadphoneOutput::headphones) != null
        downmix.binaural = value.klang.binauralEnabled && phones
        headphones.apply(value.klang, phones, audioManager?.let(HeadphoneOutput::attenuationDb) ?: 0f)
        delay.delayMs = delayMs
        // Mono is the chain's job here; the controller has no mono of its own. Levelling is the limiter's input gain.
        effects.apply(value.klang, replayGain)
        truePeak.gainDb = LoudnessTargets.gainDb(value.klang, replayGain)
        applyPassThrough()
    }

    private fun onRoute() {
        // A new output: the old one's mixer goes back, the new one gets the film's rate while it plays.
        output.refresh()
        if (player?.isPlaying == true) output.onConfigure(output.track)
        applyDeviceDelay()
        // The media route may follow the device callback a moment later (Bluetooth connecting).
        handler.postDelayed({ applyDeviceDelay(); notifyChanged() }, ROUTE_SETTLE_MS)
        applyToChain()
        notifyChanged()
    }

    private fun notifyChanged() {
        if (!released) onChanged?.invoke()
    }

    fun release() {
        released = true
        onChanged = null
        onUnderstandBetter = null
        spatial.stop()
        audioManager?.unregisterAudioDeviceCallback(routeCallback)
        context.contentResolver.unregisterContentObserver(volumeObserver)
        player?.removeListener(listener)
        player = null
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        loudnessWorker.shutdownNow()
        effects.release()
        output.release()
    }

    private companion object {
        const val TAG = "OrdoVideoSound"
        const val LOUDNESS_MAX_MILLIS = 10 * 60_000L
        const val ROUTE_SETTLE_MS = 1000L

        /** Only files on this device are measured: a network film would be read a second time. */
        fun localFile(mediaId: String): File? = runCatching {
            Paths.get(URI.create(mediaId)).takeIf { it.isLinuxPath }?.let { File(it.toString()) }
        }.getOrNull()
    }
}
