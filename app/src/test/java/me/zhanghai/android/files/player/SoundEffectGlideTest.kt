/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Equalizer, gain, clarity and bass reach a new value in small steps instead of one jump. */
class SoundEffectGlideTest {
    private fun path(from: Float, to: Float): List<Float> {
        val values = mutableListOf(from)
        while (values.last() != to) {
            values += SoundEffectController.moveToward(values.last(), to, 0.25f, 0.3f, SoundEffectController.DB_MAX_STEP)
            assertTrue("arrives", values.size < 200)
        }
        return values
    }

    @Test
    fun aPresetJumpOf12DbGlidesInSteps() {
        val steps = path(0f, 12f)
        assertTrue("12 dB take several steps, not one", steps.size > 10)
        steps.zipWithNext { a, b -> assertTrue(abs(b - a) <= SoundEffectController.DB_MAX_STEP) }
        // Turning the equalizer off glides back to 0 dB the same way.
        path(12f, 0f).zipWithNext { a, b -> assertTrue(b <= a) }
    }

    @Test
    fun smallChangesArriveInOneStepAndNeverOvershoot() {
        assertEquals(-3.5f, SoundEffectController.moveToward(-3.6f, -3.5f, 0.25f, 0.3f, 1f), 0f)
        assertEquals(0.75f, SoundEffectController.moveToward(1f, 0f, 0.25f, 0.1f, 1f), 0.0001f)
        assertEquals(5f, SoundEffectController.moveToward(4.9f, 5f, 0.25f, 0.3f, 1f), 0f)
    }
}
