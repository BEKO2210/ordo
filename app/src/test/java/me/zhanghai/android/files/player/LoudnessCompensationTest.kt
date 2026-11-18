/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessCompensationTest {
    @Test
    fun loudListeningIsUntouched() {
        for (volume in listOf(0.8f, 0.9f, 1f)) {
            assertTrue(LoudnessCompensation.liftDb(volume).all { it == 0f })
        }
    }

    @Test
    fun quieterLiftsTheLowsMostAndTheMidsNot() {
        val quiet = LoudnessCompensation.liftDb(0.2f)
        // 1 and 2 kHz stay, the lows rise most, the very highs a little.
        assertEquals(0f, quiet[5], 0f)
        assertEquals(0f, quiet[6], 0f)
        assertTrue(quiet[0] > quiet[2] && quiet[2] > quiet[3] && quiet[3] > quiet[4])
        assertTrue(quiet[9] > 0f && quiet[9] < quiet[0])
        // Gentle: never more than 6 dB anywhere.
        assertTrue(LoudnessCompensation.liftDb(0f).all { it <= 6f })
    }

    @Test
    fun theLiftGrowsSteadilyAsTheVolumeFalls() {
        var previous = -1f
        for (step in 10 downTo 0) {
            val lift = LoudnessCompensation.liftDb(step / 10f)[0]
            assertTrue(lift >= previous)
            previous = lift
        }
        assertEquals(1f, LoudnessCompensation.amount(0f), 0f)
        assertEquals(0.5f, LoudnessCompensation.amount(0.4f), 1e-6f)
    }
}
