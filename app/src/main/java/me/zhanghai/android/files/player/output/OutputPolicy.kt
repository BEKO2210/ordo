/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.output

import android.media.AudioFormat
import java.text.NumberFormat
import me.zhanghai.android.files.player.AudioSettings

/** One mixer setup an output offers ([android.media.AudioMixerAttributes] as plain values). */
data class MixerOption(val sampleRate: Int, val encoding: Int, val channelCount: Int, val bitPerfect: Boolean)

/** What Ordo hands Android for a song: the PCM encoding it writes (`AudioFormat.ENCODING_*`), rate and channels. */
data class TrackFormat(val sampleRate: Int, val encoding: Int, val channelCount: Int)

/** How the output's mixer is set for the song playing. */
sealed interface OutputPlan {
    /** Android's own mixer setting; it may resample. */
    data object System : OutputPlan

    /** The samples reach the USB DAC unchanged (`MIXER_BEHAVIOR_BIT_PERFECT`). */
    data class BitPerfect(val mixer: MixerOption) : OutputPlan

    /** The mixer still mixes, but at the song's rate, so Android does not resample. */
    data class RateMatch(val mixer: MixerOption) : OutputPlan
}

/**
 * The rules for Ordo's output path, without Android in them. `setPreferredMixerAttributes` exists from API 34:
 * https://developer.android.com/reference/android/media/AudioManager#setPreferredMixerAttributes(android.media.AudioAttributes,%20android.media.AudioDeviceInfo,%20android.media.AudioMixerAttributes)
 */
object OutputPolicy {
    const val MIN_SDK = 34

    /**
     * Bit-perfect needs a mixer that takes exactly what Ordo writes: 16-bit stays 16-bit; hi-res arrives as float
     * (exact for up to 24 bits) and is packed into 32- or 24-bit integers, or handed over as float.
     */
    fun bitPerfectEncodings(writeEncoding: Int): List<Int> = when (writeEncoding) {
        AudioFormat.ENCODING_PCM_16BIT -> listOf(AudioFormat.ENCODING_PCM_16BIT)
        AudioFormat.ENCODING_PCM_FLOAT -> listOf(
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_24BIT_PACKED, AudioFormat.ENCODING_PCM_FLOAT
        )
        else -> emptyList()
    }

    fun plan(sdk: Int, bitPerfectMode: Boolean, track: TrackFormat?, options: List<MixerOption>): OutputPlan {
        if (sdk < MIN_SDK || track == null || track.sampleRate <= 0) return OutputPlan.System
        if (bitPerfectMode) {
            fun bitPerfectMixer(writes: Int) = bitPerfectEncodings(writes).firstNotNullOfOrNull { encoding ->
                options.firstOrNull {
                    it.bitPerfect && it.encoding == encoding && it.sampleRate == track.sampleRate &&
                        it.channelCount == track.channelCount
                }
            }
            // Hard check, never "bit-perfect" for changed samples: 16-bit only counts when the decoder was offered
            // full resolution (a hi-res mixer at this rate), else the 16 bits may be a 24-bit song cut down.
            val fullResolutionOffered = bitPerfectMixer(AudioFormat.ENCODING_PCM_FLOAT) != null
            if (track.encoding != AudioFormat.ENCODING_PCM_16BIT || fullResolutionOffered) {
                bitPerfectMixer(track.encoding)?.let { return OutputPlan.BitPerfect(it) }
            }
        }
        return options.filter { !it.bitPerfect && it.sampleRate == track.sampleRate }
            // Same channels first, then the finest mixer format.
            .maxWithOrNull(compareBy<MixerOption> { it.channelCount == track.channelCount }
                .thenBy { resolution(it.encoding) })
            ?.let { OutputPlan.RateMatch(it) }
            ?: OutputPlan.System
    }

    /** What the pure sink writes for decoded PCM: hi-res as float (exact up to 24 bits), the rest as 16-bit. */
    fun pureWriteEncoding(pcmEncoding: Int, highResolution: Boolean): Int =
        if (highResolution || pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) AudioFormat.ENCODING_PCM_FLOAT
        else AudioFormat.ENCODING_PCM_16BIT

    /**
     * Per song: the DAC takes exactly this rate, channel count and bit depth bit-perfect. Only then are Ordo's stages
     * bypassed; any other song plays normally, with its effects.
     */
    fun songBitPerfect(sdk: Int, bitPerfectMode: Boolean, track: TrackFormat, options: List<MixerOption>): Boolean =
        plan(sdk, bitPerfectMode, track, options) is OutputPlan.BitPerfect

    private fun resolution(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        AudioFormat.ENCODING_PCM_32BIT -> 3
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 2
        AudioFormat.ENCODING_PCM_16BIT -> 1
        else -> 0
    }

    /** Any Ordo stage that changes the samples: Klang, platform effects, levelling, speed, skipping silence. */
    fun anyEffect(settings: AudioSettings): Boolean = with(settings) {
        equalizerEnabled || preGainDb != 0f || bassBoostEnabled || crossfeedEnabled || widthEnabled ||
            reverbEnabled || warmthEnabled || clarityEnabled || highsEnabled || quietFullEnabled ||
            normalizationEnabled || speed != 1f || skipSilence || mono || balance != 0
    }

    /** Offload decodes in the audio DSP, past Ordo's stages: only when none of them is on, and never bit-perfect. */
    fun offloadAllowed(settings: AudioSettings, bitPerfect: Boolean): Boolean = !bitPerfect && !anyEffect(settings)

    /** While bit-perfect, every Ordo stage is bypassed; the stored settings stay as they are. */
    fun bypassed(settings: AudioSettings): AudioSettings = AudioSettings(
        resumeOnHeadphones = settings.resumeOnHeadphones,
        focusBehavior = settings.focusBehavior,
        bitPerfectUsb = settings.bitPerfectUsb
    )

    /** Bits the DAC gets that carry music: 16 for 16-bit, else 24 (the float path is exact up to 24 bits). */
    fun bits(writeEncoding: Int): Int = if (writeEncoding == AudioFormat.ENCODING_PCM_16BIT) 16 else 24

    /** "24/96", "16/44,1": bits and kHz as hi-fi players show them. */
    fun label(bits: Int, sampleRate: Int, format: NumberFormat = NumberFormat.getInstance()): String =
        "$bits/" + format.apply { maximumFractionDigits = 1 }.format(sampleRate / 1000.0)
}
