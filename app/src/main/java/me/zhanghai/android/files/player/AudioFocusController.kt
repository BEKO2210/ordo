/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import androidx.media3.exoplayer.ExoPlayer

class AudioFocusController(
    context: Context,
    private val player: ExoPlayer,
    private val handler: Handler,
    private val onVolumeMultiplierChanged: (Float) -> Unit
) : AudioManager.OnAudioFocusChangeListener {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    // Kept as Any so loading this class on API 24-25 never resolves the API 26 AudioFocusRequest type.
    private var request: Any? = null
    private var hasFocus = false
    private var resumeOnGain = false
    private var behavior = AudioFocusBehavior.DUCK

    fun setBehavior(value: AudioFocusBehavior) {
        if (behavior == value) return
        behavior = value
        if (hasFocus && player.playWhenReady) {
            abandon()
            requestForPlayback()
        }
    }

    fun requestForPlayback(): Boolean {
        if (hasFocus) return true
        val result = if (Build.VERSION.SDK_INT >= 26) {
            requestFocusApi26()
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasFocus) {
            resumeOnGain = result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED
            player.pause()
        }
        return hasFocus
    }

    fun onPlaybackPaused() {
        // Keep the request only while a transient focus loss is expected to resume playback.
        if (!resumeOnGain) abandon()
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                onVolumeMultiplierChanged(1f)
                if (resumeOnGain) player.play()
                resumeOnGain = false
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnGain = false
                hasFocus = false
                onVolumeMultiplierChanged(1f)
                player.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnGain = player.playWhenReady
                hasFocus = false
                player.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (behavior == AudioFocusBehavior.PAUSE) {
                    resumeOnGain = player.playWhenReady
                    player.pause()
                } else if (Build.VERSION.SDK_INT < 26) {
                    // Android 8+ performs this ramp itself when setWillPauseWhenDucked is false.
                    // https://developer.android.com/media/optimize/audio-focus#automatic_ducking
                    onVolumeMultiplierChanged(DUCK_VOLUME)
                }
            }
        }
    }

    fun abandon() {
        if (Build.VERSION.SDK_INT >= 26) abandonApi26()
        else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        }
        request = null
        hasFocus = false
        resumeOnGain = false
        onVolumeMultiplierChanged(1f)
    }

    @android.annotation.TargetApi(26)
    private fun requestFocusApi26(): Int {
        val platformAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val focusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(platformAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(behavior == AudioFocusBehavior.PAUSE)
            .setOnAudioFocusChangeListener(this, handler)
            .build()
        request = focusRequest
        return audioManager.requestAudioFocus(focusRequest)
    }

    @android.annotation.TargetApi(26)
    private fun abandonApi26() {
        (request as? android.media.AudioFocusRequest)?.let(audioManager::abandonAudioFocusRequest)
    }

    companion object {
        private const val DUCK_VOLUME = 0.2f
    }
}
