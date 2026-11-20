/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.output

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Sets the output's mixer for the song Ordo plays (Android 14+): bit-perfect to a USB DAC when the user wants it and
 * the DAC offers the song's exact format, else the song's sample rate with normal mixing, else Android's default.
 * One per player; [release] hands the mixer back. [onConfigure] runs on the playback thread, the rest on main.
 */
class HiResOutput(context: Context, val allowsBitPerfect: Boolean) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    // Android keys the preferred mixer by usage; only USAGE_MEDIA is supported.
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()

    /** The user's "Unverfälscht an USB-DAC". */
    @Volatile var wantsBitPerfect = false

    /** A USB DAC with a bit-perfect mixer is the route and bit-perfect is wanted: Ordo's stages are bypassed. */
    @Volatile var bitPerfectMode = false
        private set

    @Volatile var plan: OutputPlan = OutputPlan.System
        private set

    /** What Ordo writes for the current song, for the badge. */
    @Volatile var track: TrackFormat? = null
        private set

    /** The integer encoding float is packed into for a bit-perfect hi-res mixer; null to write as is. */
    @Volatile var packFloatTo: Int? = null
        private set

    /** Called on any thread when [plan] changes. */
    @Volatile var onChanged: (() -> Unit)? = null

    private var appliedDevice: AudioDeviceInfo? = null
    // AudioMixerAttributes, typed Any so the field does not need API 34.
    private var appliedMixer: Any? = null

    /** Re-reads the route; true when [bitPerfectMode] flipped (the player then has to rebuild its audio). */
    @Synchronized
    fun refresh(): Boolean {
        val device = routedDevice()
        val mode = allowsBitPerfect && wantsBitPerfect && Build.VERSION.SDK_INT >= OutputPolicy.MIN_SDK &&
            device != null && isUsb(device) && mixerOptions(device).any { it.bitPerfect }
        // Unplugged or switched off: give the mixer back at once.
        if (appliedDevice != null && (appliedDevice?.id != device?.id || (!mode && plan is OutputPlan.BitPerfect))) {
            clear()
            update(OutputPlan.System, null)
        }
        val changed = mode != bitPerfectMode
        bitPerfectMode = mode
        return changed
    }

    /** The sink is about to play [track] (null: not PCM); sets the mixer for it. */
    @Synchronized
    fun onConfigure(track: TrackFormat?) {
        this.track = track
        val device = routedDevice()
        // A new route: the old device's mixer goes back first.
        if (appliedDevice != null && appliedDevice?.id != device?.id) clear()
        if (Build.VERSION.SDK_INT < OutputPolicy.MIN_SDK || device == null) {
            update(OutputPlan.System, null)
            return
        }
        val supported = supportedMixers(device)
        var next = OutputPolicy.plan(Build.VERSION.SDK_INT, bitPerfectMode, track, supported.keys.toList())
        val mixer = when (next) {
            is OutputPlan.BitPerfect -> supported[next.mixer]
            is OutputPlan.RateMatch -> supported[next.mixer]
            OutputPlan.System -> null
        }
        // Refused (another app owns the mixer, or the DAC changed): fall back silently to Android's default.
        if (mixer == null || !setPreferred(device, mixer)) {
            next = OutputPlan.System
            clear()
        }
        val pack = (next as? OutputPlan.BitPerfect)?.mixer?.encoding
            ?.takeIf { track?.encoding == AudioFormat.ENCODING_PCM_FLOAT && it != AudioFormat.ENCODING_PCM_FLOAT }
        update(next, pack)
    }

    /** Paused: hands the mixer back, so other players of this app (Ordo Video) get a normal one; play re-sets it. */
    @Synchronized
    fun suspend() {
        clear()
        update(OutputPlan.System, null)
    }

    @Synchronized
    fun release() {
        onChanged = null
        clear()
        update(OutputPlan.System, null)
    }

    private fun update(next: OutputPlan, pack: Int?) {
        packFloatTo = pack
        if (next == plan) return
        plan = next
        onChanged?.invoke()
    }

    private fun clear() {
        val device = appliedDevice ?: return
        val mine = appliedMixer
        appliedDevice = null
        appliedMixer = null
        if (Build.VERSION.SDK_INT >= OutputPolicy.MIN_SDK) {
            runCatching { clearPreferred(device, mine) }
        }
    }

    @RequiresApi(34)
    private fun setPreferred(device: AudioDeviceInfo, mixer: AudioMixerAttributes): Boolean {
        val current = runCatching { audioManager.getPreferredMixerAttributes(attributes, device) }.getOrNull()
        if (current == mixer && appliedDevice?.id == device.id) return true
        val done = runCatching { audioManager.setPreferredMixerAttributes(attributes, device, mixer) }
            .getOrDefault(false)
        if (done) {
            appliedDevice = device
            appliedMixer = mixer
        }
        return done
    }

    // Only Ordo's own setting: if the other player (music or video) set another one meanwhile, it stays.
    @RequiresApi(34)
    private fun clearPreferred(device: AudioDeviceInfo, mine: Any?) {
        if (audioManager.getPreferredMixerAttributes(attributes, device) != mine) return
        audioManager.clearPreferredMixerAttributes(attributes, device)
    }

    private fun mixerOptions(device: AudioDeviceInfo): List<MixerOption> =
        if (Build.VERSION.SDK_INT >= OutputPolicy.MIN_SDK) supportedMixers(device).keys.toList() else emptyList()

    @RequiresApi(34)
    private fun supportedMixers(device: AudioDeviceInfo): Map<MixerOption, AudioMixerAttributes> =
        runCatching { audioManager.getSupportedMixerAttributes(device) }.getOrDefault(emptyList())
            .associateBy {
                MixerOption(
                    it.format.sampleRate, it.format.encoding, Integer.bitCount(it.format.channelMask),
                    it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                )
            }

    /** Where media plays now (API 33 knows it; before, nothing is set anyway). */
    private fun routedDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < OutputPolicy.MIN_SDK) return null
        val routed = runCatching { audioManager.getAudioDevicesForAttributes(attributes) }.getOrNull()
            ?.firstOrNull() ?: return null
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == routed.type && it.address == routed.address }
    }

    companion object {
        fun isUsb(device: AudioDeviceInfo): Boolean =
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }
}
