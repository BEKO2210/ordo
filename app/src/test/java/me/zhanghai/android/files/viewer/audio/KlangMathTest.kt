/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KlangMathTest {
    @Test
    fun gainsSnapToHalfDecibelsInsideTheRange() {
        assertEquals(0.5f, KlangMath.quantizeGain(0.3f), 0f)
        assertEquals(12f, KlangMath.quantizeGain(30f), 0f)
        assertEquals(-12f, KlangMath.quantizeGain(-30f), 0f)
    }

    @Test
    fun plotFractionMapsTopToMaximumAndBottomToMinimum() {
        assertEquals(12f, KlangMath.gainAtFraction(0f), 0f)
        assertEquals(0f, KlangMath.gainAtFraction(0.5f), 0f)
        assertEquals(-12f, KlangMath.gainAtFraction(1f), 0f)
    }

    @Test
    fun speedOptionIsFoundOnlyForExactButtons() {
        assertEquals(1, KlangMath.speedOptionIndex(1f))
        assertEquals(0, KlangMath.speedOptionIndex(0.75f))
        assertEquals(-1, KlangMath.speedOptionIndex(1.05f))
    }

    @Test
    fun strengthAndBalanceMapToTheirPictures() {
        assertEquals(50, KlangMath.strengthPercent(500))
        assertEquals(100, KlangMath.strengthPercent(5_000))
        assertEquals(0.5f, KlangMath.balanceFraction(0), 0f)
        assertEquals(1f, KlangMath.balanceFraction(100), 0f)
        assertEquals(0f, KlangMath.balanceFraction(-300), 0f)
    }

    @Test
    fun aRunningTimerNeverShowsZeroMinutes() {
        assertEquals(1, KlangMath.remainingMinutes(0))
        assertEquals(1, KlangMath.remainingMinutes(1))
        assertEquals(2, KlangMath.remainingMinutes(60_001))
        assertEquals(15, KlangMath.remainingMinutes(15 * 60_000L))
    }

    @Test
    fun flatMeansEveryBandAtZero() {
        assertTrue(KlangMath.isFlat(List(10) { 0f }))
        assertFalse(KlangMath.isFlat(List(10) { if (it == 3) 0.5f else 0f }))
    }

    @Test
    fun effectValuesMapToTheirPictures() {
        assertEquals(1f / 3, KlangMath.levelFraction(1), 0.0001f)
        assertEquals(1f, KlangMath.levelFraction(9), 0f)
        assertEquals(0f, KlangMath.widthFraction(0), 0f)
        assertEquals(1f, KlangMath.widthFraction(150), 0f)
        assertEquals(0.25f, KlangMath.roomFraction(me.zhanghai.android.files.player.ReverbPreset.ROOM), 0f)
        assertEquals(1f, KlangMath.roomFraction(me.zhanghai.android.files.player.ReverbPreset.HALL), 0f)
        assertEquals(0.5f, KlangMath.sleepFraction(false, 30, 15 * 60_000L), 0.0001f)
        assertEquals(1f, KlangMath.sleepFraction(true, 0, 0L), 0f)
        assertEquals(0f, KlangMath.sleepFraction(false, 0, 1_000L), 0f)
    }
}
