/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import me.zhanghai.android.files.viewer.video.picture.PicturePolicy.Level
import me.zhanghai.android.files.viewer.video.picture.PicturePolicy.Off
import me.zhanghai.android.files.viewer.video.picture.PicturePolicy.Pipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The EETF values are BT.2408-9 Annex 5 (formerly BT.2390-8) computed independently in Python (double precision, the
 * annex's formulas typed in from the text, 21.11.2026); the PQ values are those of BT.2408 Table 1.
 */
class HdrToneMapTest {
    @Test
    fun pqMatchesBt2408ReferenceLevels() {
        assertEquals(0.508078, HdrToneMap.pq(100.0), 1e-6)
        // HDR reference white: 58 % PQ.
        assertEquals(0.580689, HdrToneMap.pq(203.0), 1e-6)
        assertEquals(0.751827, HdrToneMap.pq(1000.0), 1e-6)
        assertEquals(1.0, HdrToneMap.pq(10_000.0), 1e-12)
        for (nits in listOf(0.01, 1.0, 203.0, 4000.0)) assertEquals(nits, HdrToneMap.pqToNits(HdrToneMap.pq(nits)), nits * 1e-9)
    }

    @Test
    fun eetfIsOneToOneBelowTheKneeAndEndsAtTheTarget() {
        // 1000 cd/m² master on a 500 cd/m² screen: knee at 352 cd/m².
        assertEquals(100.0, HdrToneMap.eetf(100.0, 1000.0, 500.0, 0.5, 0.0), 1e-9)
        assertEquals(203.0, HdrToneMap.eetf(203.0, 1000.0, 500.0, 0.5, 0.0), 1e-9)
        assertEquals(394.162890, HdrToneMap.eetf(400.0, 1000.0, 500.0, 0.5, 0.0), 1e-5)
        assertEquals(499.816510, HdrToneMap.eetf(900.0, 1000.0, 500.0, 0.5, 0.0), 1e-5)
        assertEquals(500.0, HdrToneMap.eetf(1000.0, 1000.0, 500.0, 0.5, 0.0), 1e-9)
        // Onto the reference white (the SDR fallback): knee at 87.8 cd/m².
        assertEquals(50.0, HdrToneMap.eetf(50.0, 1000.0, 203.0, 0.5, 0.0), 1e-9)
        assertEquals(99.361091, HdrToneMap.eetf(100.0, 1000.0, 203.0, 0.5, 0.0), 1e-5)
        assertEquals(159.041865, HdrToneMap.eetf(203.0, 1000.0, 203.0, 0.5, 0.0), 1e-5)
        assertEquals(193.609060, HdrToneMap.eetf(400.0, 1000.0, 203.0, 0.5, 0.0), 1e-5)
        assertEquals(203.0, HdrToneMap.eetf(1000.0, 1000.0, 203.0, 0.5, 0.0), 1e-9)
        // A 4000 cd/m² scene keeps its order instead of clipping at 1000 as a fixed curve would.
        assertEquals(455.956827, HdrToneMap.eetf(1000.0, 4000.0, 500.0, 0.5, 0.0), 1e-5)
    }

    @Test
    fun eetfKeepsHighlightsInOrderAndLeavesFittingScenesAlone() {
        // WISSENSCHAFT.md §1.2.4: 900/950/1000 cd/m² stay apart. Measured on the Tab S9 (peak 1083, target 540):
        // with Ordo's knee and end slope at least three 8-bit codes apart; the annex as written merges them.
        fun code(nits: Double, knee: Double = HdrToneMap.KNEE_OFFSET, slope: Double = HdrToneMap.END_SLOPE) =
            Math.pow(HdrToneMap.eetf(nits, 1083.0, 540.0, knee, slope) / 540.0, 1 / 2.2) * 255
        val stripes = listOf(900.0, 950.0, 1000.0).map { code(it) }
        assertTrue("$stripes", stripes[1] - stripes[0] >= 3.0 && stripes[2] - stripes[1] >= 3.0)
        assertTrue(code(1000.0, 0.5, 0.0) - code(900.0, 0.5, 0.0) < 0.5)
        // The grey ramp 400…1000 cd/m²: every 100 cd/m² at least 4 codes apart (the annex: 1 at the top).
        val ramp = (4..10).map { code(it * 100.0) }
        assertTrue("$ramp", ramp.zipWithNext().all { (a, b) -> b - a >= 4.0 })
        // Reference white stays 1:1.
        assertEquals(203.0, HdrToneMap.eetf(203.0, 1083.0, 540.0), 1e-9)
        // A dim scene measured at 180 cd/m² is not compressed at all on a 500 cd/m² screen.
        assertEquals(180.0, HdrToneMap.eetf(180.0, 180.0, 500.0), 1e-12)
        // Above the measured peak (between measurements): ends at the target, never beyond.
        assertEquals(500.0, HdrToneMap.eetf(2000.0, 1000.0, 500.0), 1e-9)
        // Monotonic and never above the target, for targets 100 to 1000 and peaks up to 10 000.
        for (target in listOf(100.0, 203.0, 540.0, 1000.0)) {
            for (peak in listOf(target * 1.1, 1000.0, 4000.0, 10_000.0)) {
                var last = 0.0
                for (step in 1..2000) {
                    val out = HdrToneMap.eetf(peak * step / 2000, peak, target)
                    assertTrue("monotonic at $step of $peak on $target", out >= last - 1e-9)
                    assertTrue(out <= maxOf(target, peak * step / 2000) + 1e-9)
                    last = out
                }
            }
        }
    }

    @Test
    fun outputTableGivesTheSdrCodeThroughMedia3sPqStep() {
        val size = 1024
        val table = HdrToneMap.outputTable(size)
        // Media3's last step: code = PQ(x · 1000 cd/m²). The shader interpolates linearly between entries.
        var worst = 0.0
        for (i in 0..20_000) {
            val sdr = i / 20_000.0
            val position = Math.sqrt(sdr) * (size - 1)
            val low = position.toInt().coerceAtMost(size - 2)
            val x = table[low] + (table[low + 1] - table[low]) * (position - low)
            worst = maxOf(worst, Math.abs(HdrToneMap.pq(x * 1000.0) - Math.pow(sdr, 1 / 2.2)))
        }
        assertTrue("worst error ${worst * 1023} codes", worst * 1023 < 1.0)
    }

    @Test
    fun curveTableMatchesTheEetfAsTheShaderReadsIt() {
        val size = 256
        for ((peak, target) in listOf(1083.0 to 540.0, 4000.0 to 203.0, 600.0 to 540.0)) {
            val table = HdrToneMap.curveTable(peak, target, size)
            val knee = HdrToneMap.kneeNits(peak, target)
            assertEquals(knee, HdrToneMap.eetf(knee, peak, target), knee * 1e-6)
            for (step in 0..4000) {
                val m = knee + (peak * 1.5 - knee) * step / 4000
                val position = Math.sqrt(minOf((m - knee) / (peak - knee), 1.0)) * (size - 1)
                val low = position.toInt().coerceAtMost(size - 2)
                val y = (table[low] + (table[low + 1] - table[low]) * (position - low)) * target
                // Within 0.2 % of the light: under a third of an 8-bit code after gamma 2.2.
                assertEquals("$m of $peak on $target", HdrToneMap.eetf(m, peak, target), y, y * 2e-3)
            }
        }
        assertTrue(HdrToneMap.kneeNits(180.0, 540.0).isInfinite())
    }

    @Test
    fun targetIsTheScreensPeakOrTheReferenceWhite() {
        assertEquals(450.0, HdrToneMap.target(450f), 0.0)
        assertEquals(HdrToneMap.REFERENCE_WHITE, HdrToneMap.target(0f), 0.0)
        assertEquals(HdrToneMap.REFERENCE_WHITE, HdrToneMap.target(50_000f), 0.0)
    }

    @Test
    fun framePeakIgnoresAFewHotBlocks() {
        val blocks = DoubleArray(9216) { HdrToneMap.pq(100.0) }
        for (i in 0 until 5) blocks[i] = 1.0
        assertEquals(HdrToneMap.pq(100.0), HdrToneMap.framePeak(blocks), 2.0 / 1023)
        for (i in 0 until 200) blocks[i] = HdrToneMap.pq(1000.0)
        assertEquals(HdrToneMap.pq(1000.0), HdrToneMap.framePeak(blocks), 2.0 / 1023)
    }

    @Test
    fun sceneTrackerSmoothsWithinAShotAndJumpsAtACut() {
        val tracker = HdrToneMap.SceneTracker()
        val dim = HdrToneMap.pq(180.0)
        val bright = HdrToneMap.pq(1000.0)
        assertEquals(dim, tracker.update(dim, 0.30), 0.0)
        // Same shot, a highlight appears: one frame moves it by a twentieth only.
        val step = tracker.update(bright, 0.305)
        assertEquals(dim + (bright - dim) / 20, step, 1e-12)
        assertEquals(0, tracker.cuts)
        // The average jumps by 10 % PQ: a cut, the new scene counts at once.
        assertEquals(bright, tracker.update(bright, 0.40), 1e-12)
        assertEquals(1, tracker.cuts)
        // In between (2 % PQ): half way from the slow step to a full one.
        val before = tracker.peak
        val mid = tracker.update(dim, 0.42)
        val weight = 1.0 / 20 + (1 - 1.0 / 20) * 0.5
        assertEquals(before + (dim - before) * weight, mid, 1e-9)
        assertEquals(1, tracker.cuts)
    }

    @Test
    fun smoothRiseFollowsWithoutCuts() {
        val tracker = HdrToneMap.SceneTracker()
        var average = 0.30
        var peak = HdrToneMap.pq(200.0)
        tracker.update(peak, average)
        repeat(96) {
            average += 0.0005
            peak = HdrToneMap.pq(200.0 + 800.0 * (it + 1) / 96)
            tracker.update(peak, average)
        }
        assertEquals(0, tracker.cuts)
        assertTrue(tracker.peak > HdrToneMap.pq(800.0))
    }

    @Test
    fun budgetIsATenthOfTheFrameAndNeedsTwoSlowSecondsInARow() {
        assertEquals(4.1667, HdrToneMap.budgetMs(24f), 1e-4)
        assertEquals(1.6667, HdrToneMap.budgetMs(60f), 1e-4)
        assertEquals(HdrToneMap.DEFAULT_BUDGET_MS, HdrToneMap.budgetMs(0f), 0.0)
        // Tab S9, 4K at 24 fps: 2.2-2.7 ms stays within 4.2 ms; the same at 60 fps is over 1.7 ms.
        assertEquals(0, HdrToneMap.slowSeconds(0, 2.7, 24, steady = true, fps = 24f))
        val first = HdrToneMap.slowSeconds(0, 2.2, 60, steady = true, fps = 60f)
        assertEquals(1, first)
        assertEquals(HdrToneMap.SLOW_SECONDS, HdrToneMap.slowSeconds(first, 2.2, 60, steady = true, fps = 60f))
        // One good second in between starts again.
        assertEquals(0, HdrToneMap.slowSeconds(first, 1.0, 60, steady = true, fps = 60f))
        // Not while settling, nor over too few frames.
        assertEquals(0, HdrToneMap.slowSeconds(1, 9.0, 60, steady = false, fps = 60f))
        assertEquals(0, HdrToneMap.slowSeconds(1, 9.0, 5, steady = true, fps = 60f))
    }

    @Test
    fun toneMapsOnlyWhereTheScreenCantShowIt() {
        fun maps(
            setting: String? = "auto", hdr: Boolean = true, shows: Boolean = false, drm: Boolean = false,
            software: Boolean = false, failed: Boolean = false, slow: Boolean = false
        ) = PicturePolicy.toneMaps(setting, hdr, shows, drm, software, failed, slow)
        assertTrue(maps())
        assertTrue(maps(setting = null))
        assertFalse(maps(setting = "off"))
        assertFalse(maps(hdr = false))
        assertFalse(maps(shows = true))
        assertFalse(maps(drm = true))
        assertFalse(maps(software = true))
        assertFalse(maps(failed = true))
        assertFalse(maps(slow = true))
    }

    @Test
    fun pipelineForTheHdrPass() {
        fun decision(toneMap: Boolean, hdr: Boolean = true) = PicturePolicy.decide(
            Level.BALANCED, true, hdr, false, 3840, 2160, 2400, 1350, false, toneMap = toneMap
        )
        val mapped = decision(true)
        assertEquals(Off.HDR, mapped.off)
        assertTrue(mapped.toneMap)
        assertEquals(Pipeline.ENGAGE, PicturePolicy.pipeline(false, mapped))
        assertEquals(Pipeline.APPLY, PicturePolicy.pipeline(true, mapped, engagedToneMap = true))
        // A pipeline built for SDR can't take the HDR pass, and the other way round: a new screen.
        assertEquals(Pipeline.RECREATE, PicturePolicy.pipeline(true, mapped, engagedToneMap = false))
        assertEquals(Pipeline.RECREATE, PicturePolicy.pipeline(true, decision(false, hdr = false), engagedToneMap = true))
        // Too slow or switched off: Android maps it again.
        assertEquals(Pipeline.RECREATE, PicturePolicy.pipeline(true, decision(false), engagedToneMap = true))
        assertEquals(Pipeline.KEEP_PLAIN, PicturePolicy.pipeline(false, decision(false)))
    }
}
