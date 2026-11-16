/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSettingsTest {
    @Test
    fun everyPresetHasTenSafeBands() {
        EqualizerPreset.entries.forEach { preset ->
            assertEquals(AudioSettings.BAND_COUNT, preset.gainsDb.size)
            assertTrue(preset.gainsDb.all { it in AudioSettings.MIN_GAIN_DB..AudioSettings.MAX_GAIN_DB })
        }
        assertTrue(EqualizerPreset.FLAT.gainsDb.all { it == 0f })
        assertEquals(EqualizerPreset.FLAT.gainsDb, EqualizerPreset.CUSTOM.gainsDb)
    }

    @Test
    fun persistedSettingsRoundTripAndSanitize() {
        val original = AudioSettings(
            equalizerEnabled = true,
            preset = EqualizerPreset.ROCK,
            bandGainsDb = EqualizerPreset.ROCK.gainsDb,
            preGainDb = 3.5f,
            bassBoostEnabled = true,
            bassBoostStrength = 750,
            virtualizerEnabled = true,
            virtualizerStrength = 400,
            normalizationEnabled = true,
            normalizationPreampDb = -2f,
            speed = 1.25f,
            skipSilence = true,
            mono = true,
            balance = -20,
            resumeOnHeadphones = true,
            focusBehavior = AudioFocusBehavior.PAUSE
        )
        assertEquals(original, AudioSettingsCodec.decode(AudioSettingsCodec.encode(original)))

        val unsafe = original.copy(bandGainsDb = listOf(99f), speed = 5f, balance = -999)
        val restored = AudioSettingsCodec.decode(AudioSettingsCodec.encode(unsafe))
        assertEquals(12f, restored.bandGainsDb.first(), 0f)
        assertEquals(10, restored.bandGainsDb.size)
        assertEquals(2f, restored.speed, 0f)
        assertEquals(-100, restored.balance)
        assertEquals(1f, AudioSettings(speed = Float.NaN).sanitized().speed, 0f)
    }

    @Test
    fun corruptPersistenceFallsBackToSafeDefaults() {
        assertEquals(AudioSettings(), AudioSettingsCodec.decode("not json"))
        assertFalse(AudioSettingsCodec.decode(null).resumeOnHeadphones)
    }

    @Test
    fun replayGainHonorsPeakClippingProtection() {
        assertEquals(4f, ReplayGain.effectiveGainDb(-2f, 6f, null), 0.0001f)
        assertEquals(0f, ReplayGain.effectiveGainDb(6f, 0f, 1f), 0.0001f)
        assertEquals(6.0206f, ReplayGain.effectiveGainDb(12f, 0f, 0.5f), 0.001f)
        assertEquals(-7.5f, ReplayGain.parseDb("-7.5 dB")!!, 0f)
        assertEquals(1.5f, ReplayGain.parseDb("+1.5dB")!!, 0f)
        assertEquals(-2f, ReplayGain.parseR128("-512")!!, 0f)
    }

    @Test
    fun sleepTimerFadesOnlyDuringFinalTenSeconds() {
        assertEquals(3_600_500L, SleepTimerSchedule.deadline(500L, 60))
        assertEquals(1f, SleepTimerSchedule.volume(20_000L, 0L), 0f)
        assertEquals(0.5f, SleepTimerSchedule.volume(20_000L, 15_000L), 0f)
        assertEquals(0f, SleepTimerSchedule.volume(20_000L, 21_000L), 0f)
        assertEquals(0.5f, SleepTimerSchedule.endOfTrackVolume(30_000L, 25_000L), 0f)
        assertEquals(1f, SleepTimerSchedule.endOfTrackVolume(-1L, 0L), 0f)
    }

    @Test
    fun monoMixUsesConstantGainAndCannotOverflow() {
        assertEquals(0, MonoAudioProcessor.mixStereoSample(32_767, -32_767).toInt())
        assertEquals(32_767, MonoAudioProcessor.mixStereoSample(32_767, 32_767).toInt())
        assertEquals(-32_768, MonoAudioProcessor.mixStereoSample(-32_768, -32_768).toInt())
    }
}
