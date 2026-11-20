/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.output

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.NumberFormat
import java.util.Locale
import me.zhanghai.android.files.player.AudioFocusBehavior
import me.zhanghai.android.files.player.AudioSettings
import me.zhanghai.android.files.player.AudioSettingsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputPolicyTest {
    private val pcm16 = AudioFormat.ENCODING_PCM_16BIT
    private val pcm24 = AudioFormat.ENCODING_PCM_24BIT_PACKED
    private val pcm32 = AudioFormat.ENCODING_PCM_32BIT
    private val float = AudioFormat.ENCODING_PCM_FLOAT

    // A typical USB DAC: bit-perfect at 16 and 32 bits for 44.1 to 192 kHz, plus the normal mixers.
    private val dac = listOf(44_100, 48_000, 96_000, 192_000).flatMap { rate ->
        listOf(
            MixerOption(rate, pcm16, 2, bitPerfect = true),
            MixerOption(rate, pcm32, 2, bitPerfect = true),
            MixerOption(rate, pcm16, 2, bitPerfect = false),
            MixerOption(rate, float, 2, bitPerfect = false)
        )
    }

    @Test
    fun nothingBeforeAndroid14() {
        assertEquals(OutputPlan.System, OutputPolicy.plan(33, true, TrackFormat(96_000, float, 2), dac))
    }

    @Test
    fun bitPerfectMatchesTheSongExactly() {
        assertEquals(
            OutputPlan.BitPerfect(MixerOption(44_100, pcm16, 2, true)),
            OutputPolicy.plan(34, true, TrackFormat(44_100, pcm16, 2), dac)
        )
        // Hi-res arrives as float and goes into the DAC's 32-bit integers.
        assertEquals(
            OutputPlan.BitPerfect(MixerOption(96_000, pcm32, 2, true)),
            OutputPolicy.plan(34, true, TrackFormat(96_000, float, 2), dac)
        )
    }

    @Test
    fun packed24WhenTheDacHasNo32() {
        val options = listOf(MixerOption(96_000, pcm24, 2, true))
        assertEquals(
            OutputPlan.BitPerfect(options[0]),
            OutputPolicy.plan(34, true, TrackFormat(96_000, float, 2), options)
        )
    }

    @Test
    fun rateMatchWhenBitPerfectIsOffOrImpossible() {
        // Off: the normal mixer at the song's rate, the finest format.
        assertEquals(
            OutputPlan.RateMatch(MixerOption(96_000, float, 2, false)),
            OutputPolicy.plan(34, false, TrackFormat(96_000, float, 2), dac)
        )
        // On, but no bit-perfect mixer for mono: no bit-perfect claim, still no resampling.
        assertEquals(
            OutputPlan.RateMatch(MixerOption(48_000, float, 2, false)),
            OutputPolicy.plan(34, true, TrackFormat(48_000, pcm16, 1), dac)
        )
        // A rate the output does not offer: Android's default, silently.
        assertEquals(OutputPlan.System, OutputPolicy.plan(34, true, TrackFormat(88_200, float, 2), dac))
        // Not PCM (offload): nothing to match.
        assertEquals(OutputPlan.System, OutputPolicy.plan(34, false, null, dac))
        // The speaker usually offers nothing.
        assertEquals(OutputPlan.System, OutputPolicy.plan(34, false, TrackFormat(44_100, pcm16, 2), emptyList()))
    }

    @Test
    fun sixteenBitNeverGoesToAWiderBitPerfectMixer() {
        val only32 = listOf(MixerOption(44_100, pcm32, 2, true))
        assertEquals(OutputPlan.System, OutputPolicy.plan(34, true, TrackFormat(44_100, pcm16, 2), only32))
    }

    @Test
    fun offloadOnlyWithoutAnyStageAndNeverBitPerfect() {
        val neutral = AudioSettings()
        assertTrue(OutputPolicy.offloadAllowed(neutral, bitPerfect = false))
        assertFalse(OutputPolicy.offloadAllowed(neutral, bitPerfect = true))
        listOf(
            neutral.copy(equalizerEnabled = true), neutral.copy(bassBoostEnabled = true),
            neutral.copy(crossfeedEnabled = true), neutral.copy(widthEnabled = true),
            neutral.copy(reverbEnabled = true), neutral.copy(warmthEnabled = true),
            neutral.copy(clarityEnabled = true), neutral.copy(highsEnabled = true),
            neutral.copy(quietFullEnabled = true), neutral.copy(normalizationEnabled = true),
            neutral.copy(speed = 1.25f), neutral.copy(skipSilence = true), neutral.copy(mono = true),
            neutral.copy(balance = -10), neutral.copy(preGainDb = 1f)
        ).forEach { assertFalse(it.toString(), OutputPolicy.offloadAllowed(it, bitPerfect = false)) }
        // Not stages: they leave the samples alone.
        assertTrue(
            OutputPolicy.offloadAllowed(
                neutral.copy(resumeOnHeadphones = true, focusBehavior = AudioFocusBehavior.PAUSE), false
            )
        )
    }

    @Test
    fun bitPerfectBypassesEveryStageButKeepsTheRest() {
        val busy = AudioSettings(
            equalizerEnabled = true, reverbEnabled = true, normalizationEnabled = true, speed = 1.5f,
            skipSilence = true, mono = true, resumeOnHeadphones = true, focusBehavior = AudioFocusBehavior.PAUSE,
            bitPerfectUsb = true
        )
        val bypassed = OutputPolicy.bypassed(busy)
        assertFalse(OutputPolicy.anyEffect(bypassed))
        assertTrue(bypassed.resumeOnHeadphones)
        assertEquals(AudioFocusBehavior.PAUSE, bypassed.focusBehavior)
        assertTrue(bypassed.bitPerfectUsb)
    }

    @Test
    fun settingIsStoredAndOffByDefault() {
        assertFalse(AudioSettings().bitPerfectUsb)
        assertFalse(AudioSettingsCodec.decode("""{"eq":true}""").bitPerfectUsb)
        assertTrue(AudioSettingsCodec.decode(AudioSettingsCodec.encode(AudioSettings(bitPerfectUsb = true))).bitPerfectUsb)
    }

    @Test
    fun badgeLabel() {
        val german = NumberFormat.getInstance(Locale.GERMANY)
        assertEquals("24/96", OutputPolicy.label(OutputPolicy.bits(float), 96_000, german))
        assertEquals("16/44,1", OutputPolicy.label(OutputPolicy.bits(pcm16), 44_100, german))
        assertEquals("24/88.2", OutputPolicy.label(24, 88_200, NumberFormat.getInstance(Locale.US)))
    }

    @Test
    fun packingIsExactFor24BitSamples() {
        // What a decoder gives for 24-bit integers: x / 2^23 as float, exact.
        val samples = intArrayOf(0, 1, -1, 8_388_607, -8_388_608, 1_234_567, -7_654_321)
        val floats = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.nativeOrder())
        samples.forEach { floats.putFloat(it / 8_388_608f) }

        floats.flip()
        val out32 = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.nativeOrder())
        PcmPacking.pack(floats.duplicate().order(ByteOrder.nativeOrder()), out32, PcmPacking32)
        out32.flip()
        samples.forEach { assertEquals(it.toLong() shl 8, out32.int.toLong()) }

        val out24 = ByteBuffer.allocate(samples.size * 3)
        PcmPacking.pack(floats.duplicate().order(ByteOrder.nativeOrder()), out24, PcmPacking24)
        out24.flip()
        samples.forEach {
            val value = (out24.get().toInt() and 0xFF) or ((out24.get().toInt() and 0xFF) shl 8) or
                (out24.get().toInt() shl 16)
            assertEquals(it, value)
        }
    }

    @Test
    fun packingClipsInsteadOfWrapping() {
        val floats = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putFloat(1f).putFloat(-1.5f)
        floats.flip()
        val out = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        PcmPacking.pack(floats, out, PcmPacking32)
        out.flip()
        assertEquals(Int.MAX_VALUE, out.int)
        assertEquals(Int.MIN_VALUE, out.int)
    }

    private companion object {
        // Media3's C.ENCODING_PCM_32BIT / C.ENCODING_PCM_24BIT are AudioFormat's values.
        const val PcmPacking32 = AudioFormat.ENCODING_PCM_32BIT
        const val PcmPacking24 = AudioFormat.ENCODING_PCM_24BIT_PACKED
    }
}
