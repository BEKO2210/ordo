/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSoundUiStateTest {
    @Test
    fun equalizerStatusNeverClaimsAnInactiveEqualizer() {
        assertEquals(EqualizerStatusKind.UNSUPPORTED, equalizerStatusKind(false, false))
        assertEquals(EqualizerStatusKind.UNSUPPORTED, equalizerStatusKind(false, true))
        assertEquals(EqualizerStatusKind.OFF, equalizerStatusKind(true, false))
        assertEquals(EqualizerStatusKind.PRESET, equalizerStatusKind(true, true))
    }

    @Test
    fun equalizerControlsRequireSupportAndAnEnabledEqualizer() {
        assertFalse(equalizerControlsEnabled(false, false))
        assertFalse(equalizerControlsEnabled(false, true))
        assertFalse(equalizerControlsEnabled(true, false))
        assertTrue(equalizerControlsEnabled(true, true))
    }

    @Test
    fun bitPerfectSongShowsTheEqualizerBypassedWhateverItsSetting() {
        for (available in listOf(true, false)) for (enabled in listOf(true, false)) {
            assertEquals(EqualizerStatusKind.BYPASSED, equalizerStatusKind(available, enabled, bypassed = true))
            assertFalse(equalizerControlsEnabled(available, enabled, bypassed = true))
        }
    }
}
