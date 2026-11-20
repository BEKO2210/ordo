/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.headphones

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi

/** What the sound plays through, for the headphone pack (music and video share it). */
object HeadphoneOutput {
    fun isHeadphones(device: AudioDeviceInfo): Boolean = when (device.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_HEADSET -> true
        else -> (Build.VERSION.SDK_INT >= 28 && isHearingAid(device)) ||
            (Build.VERSION.SDK_INT >= 31 && isBleHeadset(device))
    }

    @RequiresApi(28)
    private fun isHearingAid(device: AudioDeviceInfo) = device.type == AudioDeviceInfo.TYPE_HEARING_AID

    @RequiresApi(31)
    private fun isBleHeadset(device: AudioDeviceInfo) = device.type == AudioDeviceInfo.TYPE_BLE_HEADSET

    /**
     * The headphones media plays through now, if any: a connected but unused headset (Bluetooth in the bag, a USB DAC
     * while a car's speaker plays) does not count. Android 13+ names the route for media; before, any connected one.
     * https://developer.android.com/reference/android/media/AudioManager#getAudioDevicesForAttributes(android.media.AudioAttributes)
     */
    fun headphones(audioManager: AudioManager): AudioDeviceInfo? =
        routedOrConnected(
            routedDevices(audioManager), { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() },
            ::isHeadphones
        )

    /** The headphones among the routed devices; unknown route (null or empty): among the connected ones. */
    internal fun <T> routedOrConnected(routed: List<T>?, connected: () -> List<T>, isHeadphones: (T) -> Boolean): T? =
        if (routed.isNullOrEmpty()) connected().firstOrNull(isHeadphones) else routed.firstOrNull(isHeadphones)

    private fun routedDevices(audioManager: AudioManager): List<AudioDeviceInfo>? =
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { audioManager.getAudioDevicesForAttributes(MEDIA) }.getOrNull()
        } else null

    // Lazy: the pure helper above runs in JVM tests, where the platform builder is a stub.
    private val MEDIA by lazy { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build() }

    /**
     * The media volume in dB below full, for "full sound when quiet": the device's own volume curve for this output
     * (API 28+), else Android's reference media curve ([EqualLoudness.defaultCurveDb]). Muted counts as very quiet.
     */
    fun attenuationDb(audioManager: AudioManager): Float {
        val stream = AudioManager.STREAM_MUSIC
        val index = audioManager.getStreamVolume(stream)
        val max = audioManager.getStreamMaxVolume(stream)
        val min = if (Build.VERSION.SDK_INT >= 28) audioManager.getStreamMinVolume(stream) else 0
        if (index <= min) return MUTED_DB
        if (Build.VERSION.SDK_INT >= 28) {
            val type = headphones(audioManager)?.type ?: AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            val db = runCatching { audioManager.getStreamVolumeDb(stream, index, type) }.getOrNull()
            if (db != null && db.isFinite()) return db.coerceIn(MUTED_DB, 0f)
        }
        return EqualLoudness.defaultCurveDb(index, min, max)
    }

    private const val MUTED_DB = -96f
}
