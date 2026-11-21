/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.sound

import android.media.AudioDeviceInfo
import android.media.AudioManager
import me.zhanghai.android.files.player.headphones.HeadphoneOutput
import java.security.MessageDigest

/**
 * The A/V delay of the output the sound plays through: Bluetooth headphones lag by their own amount, the speaker not
 * at all. [route] loads the delay remembered for a device when it becomes the output (0 for one never set), [set]
 * keeps a new one for it. Storage sits behind two functions, so the rules run in JVM tests with a map.
 */
internal class VideoDeviceDelay(private val read: (String) -> Int?, private val write: (String, Int) -> Unit) {
    /** The output's key, null while it is unknown (then nothing is stored). */
    var key: String? = null
        private set
    var delayMs: Int = 0
        private set

    /** The output is now [newKey]; true when that changed the delay. */
    fun route(newKey: String?): Boolean {
        if (newKey == key) return false
        key = newKey
        val before = delayMs
        delayMs = newKey?.let(read) ?: 0
        return delayMs != before
    }

    fun set(value: Int) {
        delayMs = value
        key?.let { write(it, value) }
    }

    companion object {
        private val BLUETOOTH_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST
        )

        /**
         * Type and product name; a Bluetooth device adds a hash of its address, so two headphones of the same model
         * keep their own delay. The address itself (a MAC) is never stored. Wired and USB addresses change with every
         * plug, so they are left out.
         */
        fun key(type: Int, productName: CharSequence?, address: String?): String {
            val base = "$type|${productName ?: ""}"
            if (type !in BLUETOOTH_TYPES || address.isNullOrBlank()) return base
            val hash = MessageDigest.getInstance("SHA-256").digest(address.uppercase().toByteArray())
                .take(6).joinToString("") { "%02x".format(it) }
            return "$base|$hash"
        }

        fun key(device: AudioDeviceInfo): String =
            key(device.type, device.productName, if (android.os.Build.VERSION.SDK_INT >= 28) device.address else null)

        /**
         * What media plays through now: Android 13+ names the route; before, connected headphones, else the speaker.
         * shortcut: before Android 13 a USB DAC or HDMI counts as the speaker; fine until a tester has one there.
         */
        fun output(audioManager: AudioManager): AudioDeviceInfo? =
            HeadphoneOutput.routedDevices(audioManager)?.firstOrNull()
                ?: HeadphoneOutput.headphones(audioManager)
                ?: audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }
}
