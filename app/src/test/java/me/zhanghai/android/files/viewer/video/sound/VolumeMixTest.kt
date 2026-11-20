/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.sound

import org.junit.Assert.assertEquals
import org.junit.Test

/** The effect fader and the sleep timer each own a factor; the player gets the product. */
class VolumeMixTest {
    @Test
    fun faderAndSleepComposeInsteadOfOverriding() {
        var written = -1f
        val mix = VolumeMix { written = it }
        mix.sleep = 0.5f
        assertEquals(0.5f, written, 0f)
        // A duck during the sleep fade dips from 0.5, not from full volume, and comes back to 0.5.
        mix.fader = 0.2f
        assertEquals(0.1f, written, 1e-6f)
        mix.fader = 1f
        assertEquals(0.5f, written, 0f)
        // The sleep fade goes on during a duck: the fader's dip stays applied.
        mix.fader = 0.5f
        mix.sleep = 0.25f
        assertEquals(0.125f, written, 1e-6f)
        // Cancelling the timer restores only its own factor.
        mix.sleep = 1f
        assertEquals(0.5f, written, 0f)
        mix.fader = 1f
        assertEquals(1f, written, 0f)
    }

    @Test
    fun outOfRangeFactorsAreClamped() {
        var written = -1f
        val mix = VolumeMix { written = it }
        mix.fader = 2f
        assertEquals(1f, written, 0f)
        mix.sleep = -1f
        assertEquals(0f, written, 0f)
    }
}
