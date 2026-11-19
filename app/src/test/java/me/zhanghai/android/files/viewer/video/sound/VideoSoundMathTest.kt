/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VideoSoundMathTest {
    @Test
    fun centreGainsAndCoercion() {
        assertEquals(0.7071f, VideoSoundMath.centreGain(VideoSoundMath.DIALOG_OFF), 0f)
        assertEquals(1.0f, VideoSoundMath.centreGain(VideoSoundMath.DIALOG_LIGHT), 0f)
        assertEquals(1.4125f, VideoSoundMath.centreGain(VideoSoundMath.DIALOG_STRONG), 0f)
        assertEquals(0.7071f, VideoSoundMath.centreGain(-9), 0f)
        assertEquals(1.4125f, VideoSoundMath.centreGain(99), 0f)
    }

    @Test
    fun dialogTargetsAndBoost() {
        assertNull(VideoSoundMath.dialogTargetLu(VideoSoundMath.DIALOG_OFF))
        assertEquals(10f, VideoSoundMath.dialogTargetLu(VideoSoundMath.DIALOG_LIGHT)!!, 0f)
        assertEquals(15f, VideoSoundMath.dialogTargetLu(9)!!, 0f)
        assertEquals(8f, VideoSoundMath.dialogBoostDb(2f, 10f), 0f)
        assertEquals(0f, VideoSoundMath.dialogBoostDb(12f, 10f), 0f)
        assertEquals(VideoSoundMath.DIALOG_CAP_DB, VideoSoundMath.dialogBoostDb(-30f, 15f), 0f)
        assertEquals(0f, VideoSoundMath.dialogBoostDb(Float.NaN, 15f), 0f)
        assertEquals(10f, VideoSoundMath.distanceLu(1e-2, 1e-3), 1e-4f)
        assertTrue(VideoSoundMath.distanceLu(1e-8, 1e-3).isNaN())
        assertEquals(-30f, VideoSoundMath.NIGHT.thresholdDb, 0f)
    }

    @Test
    fun speechBandFilter() {
        val band = VideoSoundMath.speechBand(48_000)
        assertEquals(0f, VideoSoundMath.magnitudeDb(band, 1_095f, 48_000), 0.05f)
        assertEquals(-3f, VideoSoundMath.magnitudeDb(band, 300f, 48_000), 0.3f)
        assertEquals(-3f, VideoSoundMath.magnitudeDb(band, 4_000f, 48_000), 0.3f)
        assertTrue(VideoSoundMath.magnitudeDb(band, 60f, 48_000) < -12f)
    }

    @Test
    fun gainComputerSoftKneeAndHardKnee() {
        val t = -30f
        val r = 4f
        val w = 6f
        assertEquals(0f, VideoSoundMath.gainReductionDb(-40f, t, r, w), 1e-5f)
        assertEquals(-18f, VideoSoundMath.gainReductionDb(-6f, t, r, w), 1e-5f)
        // Continuity at both knee edges.
        val lo = t - w / 2f
        val hi = t + w / 2f
        assertEquals(0f, VideoSoundMath.gainReductionDb(lo, t, r, w), 1e-3f)
        val above = (t + (hi - t) / r) - hi
        assertEquals(above, VideoSoundMath.gainReductionDb(hi, t, r, w), 1e-3f)
        // Hard knee.
        assertEquals(0f, VideoSoundMath.gainReductionDb(-31f, t, r, 0f), 0f)
        assertEquals((t + (-6f - t) / r) - (-6f), VideoSoundMath.gainReductionDb(-6f, t, r, 0f), 1e-5f)
        assertEquals(0f, VideoSoundMath.gainReductionDb(0f, t, 1f, w), 0f)
    }

    @Test
    fun coefficientInUnitIntervalGrowsWithTime() {
        val short = VideoSoundMath.coefficient(5f, 48_000)
        val long = VideoSoundMath.coefficient(250f, 48_000)
        assertTrue(short > 0f && short < 1f)
        assertTrue(long > 0f && long < 1f)
        assertTrue(long > short)
        assertEquals(0f, VideoSoundMath.coefficient(0f, 48_000), 0f)
    }

    @Test
    fun downmixNullBoundsAndFivePointOne() {
        assertNull(VideoSoundMath.downmixMatrix(2, 0.7071f))
        assertNull(VideoSoundMath.downmixMatrix(9, 0.7071f))
        val m = VideoSoundMath.downmixMatrix(6, 0.7071f)!!
        assertEquals(2, m.size)
        assertEquals(6, m[0].size)
        assertEquals(0f, m[0][3], 0f) // LFE
        assertEquals(0f, m[1][3], 0f)
        assertEquals(VideoSoundMath.NORMALISE, m[0][0], 1e-5f) // FL only left
        assertEquals(0f, m[1][0], 0f)
        assertEquals(m[0][2], m[1][2], 0f) // centre equal
        val sum = m[0].sum()
        assertEquals((1f + 0.7071f + 0.7071f) * VideoSoundMath.NORMALISE, sum, 1e-4f)
        val weak = VideoSoundMath.downmixMatrix(6, VideoSoundMath.centreGain(0))!![0][2]
        val strong = VideoSoundMath.downmixMatrix(6, VideoSoundMath.centreGain(2))!![0][2]
        assertTrue(strong > weak)
        val seven = VideoSoundMath.downmixMatrix(8, 1f)!!
        assertTrue(seven[0][4] > 0f && seven[0][6] > 0f) // BL and SL on left
        // 6.1 is FL FR FC LFE BL BR BC: the back centre goes to both sides, BL only left, BR only right.
        val sixOne = VideoSoundMath.downmixMatrix(7, 1f)!!
        assertEquals(sixOne[0][6], sixOne[1][6], 0f)
        assertTrue(sixOne[0][4] > 0f && sixOne[1][4] == 0f && sixOne[1][5] > 0f && sixOne[0][5] == 0f)
    }

    @Test
    fun stepDelayClampsAndSnaps() {
        assertEquals(1000, VideoSoundMath.stepDelay(990, 1))
        assertEquals(-1000, VideoSoundMath.stepDelay(-980, -1))
        assertEquals(50, VideoSoundMath.stepDelay(37, 0))
        assertEquals(0, VideoSoundMath.stepDelay(20, 0))
    }

    @Test
    fun delayFramesKeepsSign() {
        assertEquals(4800L, VideoSoundMath.delayFrames(100, 48_000))
        assertEquals(-2205L, VideoSoundMath.delayFrames(-50, 44_100))
    }

    @Test
    fun channelLayoutLabels() {
        assertEquals("Mono", VideoSoundMath.channelLayout(1))
        assertEquals("Stereo", VideoSoundMath.channelLayout(2))
        assertEquals("3.0", VideoSoundMath.channelLayout(3))
        assertEquals("5.1", VideoSoundMath.channelLayout(6))
        assertEquals("7.1", VideoSoundMath.channelLayout(8))
        assertNull(VideoSoundMath.channelLayout(0))
        assertNull(VideoSoundMath.channelLayout(9))
    }

    @Test
    fun bassMappingRoundTrip() {
        assertEquals(0, VideoSoundMath.bassStrength(0))
        assertEquals(333, VideoSoundMath.bassStrength(1))
        assertEquals(667, VideoSoundMath.bassStrength(2))
        assertEquals(1000, VideoSoundMath.bassStrength(3))
        assertEquals(1000, VideoSoundMath.bassStrength(9))
        for (level in 0..3) {
            assertEquals(level, VideoSoundMath.bassLevel(VideoSoundMath.bassStrength(level)))
        }
        assertEquals(0, VideoSoundMath.bassLevel(0))
        assertEquals(2, VideoSoundMath.bassLevel(600))
    }
}
