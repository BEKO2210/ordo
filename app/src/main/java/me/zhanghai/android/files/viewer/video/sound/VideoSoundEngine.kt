/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.sound

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import me.zhanghai.android.files.player.AudioRenderersFactory
import me.zhanghai.android.files.player.KlangAudioProcessor
import me.zhanghai.android.files.player.MonoStage
import me.zhanghai.android.files.player.SoundEffectController
import me.zhanghai.android.files.player.TransportFader

/**
 * The video player's sound, built from Ordo Musik's Klang engine: [SoundEffectController] (equalizer, bass, balance
 * on the platform's DynamicsProcessing, switched only in silence or inside a short dip) and a [KlangAudioProcessor]
 * chain with mono, the dialog enhancer and night mode. Before it, [DownmixProcessor] folds 5.1/7.1 to stereo with the
 * centre kept audible; after it, [AvDelayProcessor] shifts the sound against the picture.
 *
 * [settings] and [update] are the one public way in, for the sheet today and for anything that wants to set the sound
 * later (no AI in the player itself).
 */
class VideoSoundEngine(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val mono = MonoStage()
    private val dialog = DialogStage()
    private val night = NightStage()
    private val klang = KlangAudioProcessor(arrayOf(mono, dialog, night))
    private val downmix = DownmixProcessor()
    private val delay = AvDelayProcessor()
    private var player: ExoPlayer? = null
    private val fader = TransportFader(
        { SystemClock.uptimeMillis() }, { action, delayMs -> handler.postDelayed(action, delayMs) },
        { handler.removeCallbacks(it) }
    ) { player?.volume = it }
    private val effects = SoundEffectController(handler) { action ->
        if (player?.isPlaying == true) fader.duck(action) else fader.immediate(action)
    }
    private val listener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) = effects.attach(audioSessionId)

        override fun onIsPlayingChanged(isPlaying: Boolean) = effects.setAudible(isPlaying)

        override fun onTracksChanged(tracks: Tracks) {
            sourceChannelCount = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                ?.let { group -> (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) }
                ?.channelCount ?: Format.NO_VALUE
        }
    }

    var settings: VideoSoundSettings = VideoSoundStore.read(context)
        private set

    /** Sound later (+) or earlier (−) than the picture, in ms; for this player screen only. */
    var delayMs: Int = 0
        private set

    /** Channels of the selected sound track, or -1 while unknown. */
    var sourceChannelCount: Int = Format.NO_VALUE
        private set

    /** What the device's effects allow (bass, balance), for the sheet. */
    val status: SoundEffectController.Status get() = effects.status

    fun renderersFactory(): RenderersFactory = AudioRenderersFactory(context, arrayOf(downmix, klang, delay))

    fun attach(player: ExoPlayer) {
        this.player = player
        player.addListener(listener)
        applyToChain()
        effects.attach(player.audioSessionId)
        effects.setAudible(player.isPlaying)
    }

    fun update(value: VideoSoundSettings) {
        settings = value.copy(klang = value.klang.sanitized())
        VideoSoundStore.write(context, settings)
        applyToChain()
    }

    /** A new delay takes effect with a flush: a seek to where the film is (one short re-buffer). */
    fun setDelay(value: Int) {
        val clamped = VideoSoundMath.stepDelay(value, 0)
        if (clamped == delayMs) return
        delayMs = clamped
        delay.delayMs = clamped
        player?.let { it.seekTo(it.currentPosition) }
    }

    private fun applyToChain() {
        val value = settings
        mono.effectEnabled = value.klang.mono
        if (value.dialogLevel != VideoSoundMath.DIALOG_OFF) dialog.level = value.dialogLevel
        dialog.effectEnabled = value.dialogLevel != VideoSoundMath.DIALOG_OFF
        night.effectEnabled = value.nightMode
        downmix.centreGain = VideoSoundMath.centreGain(value.dialogLevel)
        delay.delayMs = delayMs
        // Mono is the chain's job here; the controller has no mono of its own.
        effects.apply(value.klang)
    }

    fun release() {
        player?.removeListener(listener)
        player = null
        handler.removeCallbacksAndMessages(null)
        effects.release()
    }
}
