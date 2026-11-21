/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.output

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import java.text.NumberFormat
import me.zhanghai.android.files.player.AudioSettings

/** One mixer setup an output offers ([android.media.AudioMixerAttributes] as plain values). */
data class MixerOption(val sampleRate: Int, val encoding: Int, val channelCount: Int, val bitPerfect: Boolean)

/** What Ordo hands Android for a song: the PCM encoding it writes (`AudioFormat.ENCODING_*`), rate and channels. */
data class TrackFormat(val sampleRate: Int, val encoding: Int, val channelCount: Int)

/**
 * The song's own bit depth as the extractor declares it (FLAC STREAMINFO, WAV/ALAC PCM encoding); lossy codecs count
 * as 16 bits for the decision, but the badge shows no bits for them. [bits] null: unknown, never bit-perfect.
 */
data class SourceDepth(val bits: Int?, val lossy: Boolean) {
    companion object {
        val UNKNOWN = SourceDepth(null, false)
    }
}

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

    fun plan(
        sdk: Int,
        bitPerfectMode: Boolean,
        track: TrackFormat?,
        options: List<MixerOption>,
        source: SourceDepth = SourceDepth.UNKNOWN
    ): OutputPlan {
        if (sdk < MIN_SDK || track == null || track.sampleRate <= 0) return OutputPlan.System
        if (bitPerfectMode && writesUnchanged(track.encoding, source)) {
            bitPerfectEncodings(track.encoding).firstNotNullOfOrNull { encoding ->
                options.firstOrNull {
                    it.bitPerfect && it.encoding == encoding && it.sampleRate == track.sampleRate &&
                        it.channelCount == track.channelCount && mixerBits(encoding) >= source.bits!!
                }
            }?.let { return OutputPlan.BitPerfect(it) }
        }
        return options.filter { !it.bitPerfect && it.sampleRate == track.sampleRate }
            // Same channels first, then the finest mixer format.
            .maxWithOrNull(compareBy<MixerOption> { it.channelCount == track.channelCount }
                .thenBy { resolution(it.encoding) })
            ?.let { OutputPlan.RateMatch(it) }
            ?: OutputPlan.System
    }

    /**
     * Hard check: what Ordo writes still holds every bit of the song. 16-bit only for songs of up to 16 bits (a 24-bit
     * song decoded to 16 would be cut), float only up to 24 bits (its mantissa); unknown depth never.
     */
    private fun writesUnchanged(writeEncoding: Int, source: SourceDepth): Boolean {
        val bits = source.bits ?: return false
        return when (writeEncoding) {
            AudioFormat.ENCODING_PCM_16BIT -> bits <= 16
            AudioFormat.ENCODING_PCM_FLOAT -> bits <= 24
            else -> false
        }
    }

    private fun mixerBits(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> 16
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        AudioFormat.ENCODING_PCM_32BIT -> 32
        AudioFormat.ENCODING_PCM_FLOAT -> 24
        else -> 0
    }

    /** What Ordo writes for a song: up to 16 bits (and lossy) as 16-bit, deeper songs as float. */
    fun writeEncodingFor(source: SourceDepth): Int =
        if ((source.bits ?: 0) > 16) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT

    /**
     * From the extractor's track Format: the declared PCM encoding (FLAC, WAV, ALAC), else lossy codecs as 16 bits;
     * a lossless codec without a declared depth stays unknown.
     */
    fun sourceDepth(sampleMimeType: String?, pcmEncoding: Int): SourceDepth {
        val bits = when (pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
            else -> null
        }
        if (bits != null) return SourceDepth(bits, false)
        return if (sampleMimeType in LOSSLESS || sampleMimeType == null) SourceDepth.UNKNOWN else SourceDepth(16, true)
    }

    private val LOSSLESS = setOf(
        MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC, MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_TRUEHD,
        MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_WAV
    )

    /** What the pure sink writes for decoded PCM: hi-res as float (exact up to 24 bits), the rest as 16-bit. */
    fun pureWriteEncoding(pcmEncoding: Int, highResolution: Boolean): Int =
        if (highResolution || pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) AudioFormat.ENCODING_PCM_FLOAT
        else AudioFormat.ENCODING_PCM_16BIT

    /**
     * Per song: the DAC takes exactly this rate, channel count and bit depth bit-perfect. Only then are Ordo's stages
     * bypassed; any other song plays normally, with its effects.
     */
    fun songBitPerfect(
        sdk: Int,
        bitPerfectMode: Boolean,
        track: TrackFormat,
        options: List<MixerOption>,
        source: SourceDepth
    ): Boolean = plan(sdk, bitPerfectMode, track, options, source) is OutputPlan.BitPerfect

    private fun resolution(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        AudioFormat.ENCODING_PCM_32BIT -> 3
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 2
        AudioFormat.ENCODING_PCM_16BIT -> 1
        else -> 0
    }

    /**
     * Any Ordo stage that changes the samples: Klang, the headphone pack (virtual surround, correction, quiet
     * listening), platform effects, levelling, speed, skipping silence.
     */
    fun anyEffect(settings: AudioSettings): Boolean = with(settings) {
        equalizerEnabled || preGainDb != 0f || bassBoostEnabled || crossfeedEnabled || widthEnabled ||
            reverbEnabled || warmthEnabled || clarityEnabled || highsEnabled || quietFullEnabled ||
            binauralEnabled || headphoneEq.isNotEmpty() ||
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

    /** "24/96", "16/44,1": bits and kHz as hi-fi players show them. */
    fun label(bits: Int?, sampleRate: Int, format: NumberFormat = NumberFormat.getInstance()): String {
        val khz = format.apply { maximumFractionDigits = 1 }.format(sampleRate / 1000.0)
        return if (bits == null) "$khz kHz" else "$bits/$khz"
    }
}
