/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.headphones

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

    /** The connected headphones, if any. */
    fun headphones(audioManager: AudioManager): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull(::isHeadphones)

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
