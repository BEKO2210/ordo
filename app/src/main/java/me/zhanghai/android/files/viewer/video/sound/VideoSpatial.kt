/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build
import android.os.Handler
import androidx.annotation.RequiresApi

/**
 * The hand-off to the system's spatializer (API 32+): when it is available and switched on for the current output
 * and can render a 5.1 or 7.1 film, those channels go to it untouched; Ordo's own downmix would flatten them to stereo
 * first and take the system's surround away. Checked again when the spatializer's state or the output device changes.
 * https://developer.android.com/reference/android/media/Spatializer
 */
internal class VideoSpatial(
    private val context: Context,
    private val handler: Handler,
    private val onChannels: (Set<Int>) -> Unit
) {
    private var spatializer: Any? = null
    private var stateListener: Any? = null
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = check()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = check()
    }

    private var started = false

    fun start() {
        if (started) return
        started = true
        if (Build.VERSION.SDK_INT < 32) return onChannels(emptySet())
        startV32()
        context.getSystemService(AudioManager::class.java)?.registerAudioDeviceCallback(deviceCallback, handler)
        check()
    }

    fun stop() {
        if (!started) return
        started = false
        if (Build.VERSION.SDK_INT < 32) return
        context.getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(deviceCallback)
        stopV32()
    }

    private fun check() {
        if (Build.VERSION.SDK_INT >= 32) onChannels(channelsV32())
    }

    @RequiresApi(32)
    private fun startV32() {
        val spatializer = context.getSystemService(AudioManager::class.java)?.spatializer ?: return
        this.spatializer = spatializer
        val listener = object : Spatializer.OnSpatializerStateChangedListener {
            override fun onSpatializerEnabledChanged(spatializer: Spatializer, enabled: Boolean) = check()
            override fun onSpatializerAvailableChanged(spatializer: Spatializer, available: Boolean) = check()
        }
        stateListener = listener
        spatializer.addOnSpatializerStateChangedListener({ handler.post(it) }, listener)
    }

    @RequiresApi(32)
    private fun stopV32() {
        val spatializer = spatializer as? Spatializer ?: return
        (stateListener as? Spatializer.OnSpatializerStateChangedListener)
            ?.let(spatializer::removeOnSpatializerStateChangedListener)
    }

    @RequiresApi(32)
    private fun channelsV32(): Set<Int> {
        val spatializer = spatializer as? Spatializer ?: return emptySet()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
        return MASKS.filter { (_, mask) ->
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48_000)
                .setChannelMask(mask).build()
            useSystemSpatializer(
                spatializer.immersiveAudioLevel != Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE,
                spatializer.isAvailable, spatializer.isEnabled,
                runCatching { spatializer.canBeSpatialized(attributes, format) }.getOrDefault(false)
            )
        }.map { it.first }.toSet()
    }

    companion object {
        private val MASKS = listOf(
            6 to AudioFormat.CHANNEL_OUT_5POINT1,
            8 to AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        )

        /** All four must hold: the device can spatialize at all, for this output, it is on, and for this layout. */
        internal fun useSystemSpatializer(
            immersive: Boolean,
            available: Boolean,
            enabled: Boolean,
            canSpatialize: Boolean
        ): Boolean = immersive && available && enabled && canSpatialize
    }
}
