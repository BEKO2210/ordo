/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import me.zhanghai.android.files.player.EqualizerPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSoundUiStateTest {
    @Test
    fun customPresetOnlyAppearsAfterAUserAdjustment() {
        assertFalse(EqualizerPreset.CUSTOM in equalizerPresetRail(EqualizerPreset.ROCK))
        assertEquals(EqualizerPreset.CUSTOM, equalizerPresetRail(EqualizerPreset.CUSTOM).last())
    }
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
}
