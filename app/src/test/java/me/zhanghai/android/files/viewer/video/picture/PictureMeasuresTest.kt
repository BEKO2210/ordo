/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import kotlin.math.PI
import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frame pacing (WISSENSCHAFT.md §1.2.6) and the bright-room gamma of ITU-R BT.2390-12 (§1.2.5). */
class PictureMeasuresTest {
    private val vsync120 = 1e9 / 120
    private val frame24 = 1e9 / 24

    @Test
    fun twentyFourOnHundredTwentyIsFiveVsyncsEach() {
        // 1000 frames, one of them held a vsync too long: 99.9 %, the report's acceptance.
        val intervals = LongArray(1000) { if (it == 500) (frame24 + vsync120).toLong() else frame24.toLong() }
        val verdict = FramePacing.verdict(FramePacing.summarize(intervals, vsync120), 24f, 120f)
        verdict as FramePacing.Verdict.Even
        assertEquals(5, verdict.vsyncs)
        assertEquals(0.999, verdict.share, 1e-9)
        assertEquals(1000, verdict.frames)
    }

    @Test
    fun twentyFourOnSixtyIsThreeTwoPulldown() {
        val vsync60 = 1e9 / 60
        val intervals = LongArray(240) { ((if (it % 2 == 0) 3 else 2) * vsync60).toLong() }
        val verdict = FramePacing.verdict(FramePacing.summarize(intervals, vsync60), 24f, 60f)
        verdict as FramePacing.Verdict.Pulldown
        assertEquals(3, verdict.longer)
        assertEquals(2, verdict.shorter)
        assertEquals(1.0, verdict.share, 1e-9)
        assertEquals(0.5, verdict.longShare, 1e-9)
        assertEquals(0.5, verdict.expectedLongShare, 1e-9)
        // All frames at two vsyncs is no 3:2 cadence: the long share says so.
        val broken = FramePacing.summarize(LongArray(240) { (2 * vsync60).toLong() }, vsync60)
        assertEquals(0.0, (FramePacing.verdict(broken, 24f, 60f) as FramePacing.Verdict.Pulldown).longShare, 1e-9)
        // 25 fps on 120 Hz (4.8 vsyncs) is not even.
        val pal = FramePacing.summarize(LongArray(100) { 40_000_000L }, vsync120)
        assertTrue(FramePacing.verdict(pal, 25f, 120f) is FramePacing.Verdict.Pulldown)
    }

    @Test
    fun ntscRatesCountAsEven() {
        // 23.976 on 119.88 Hz and 25 on 50 Hz divide evenly.
        val summary = FramePacing.summarize(LongArray(100) { (5e9 / 119.88).toLong() }, 1e9 / 119.88)
        assertTrue(FramePacing.verdict(summary, 23.976f, 119.88f) is FramePacing.Verdict.Even)
        val pal = FramePacing.summarize(LongArray(100) { 40_000_000L }, 1e9 / 50)
        assertEquals(2, (FramePacing.verdict(pal, 25f, 50f) as FramePacing.Verdict.Even).vsyncs)
    }

    @Test
    fun tooFewFramesOrUnknownRateSayNothing() {
        assertNull(FramePacing.verdict(FramePacing.summarize(LongArray(10) { frame24.toLong() }, vsync120), 24f, 120f))
        assertNull(FramePacing.verdict(FramePacing.summarize(LongArray(100) { frame24.toLong() }, vsync120), 0f, 120f))
    }

    @Test
    fun meterSkipsPausesAndKeepsTheNewest() {
        val meter = FramePacing(capacity = 4)
        var t = 1_000_000_000L
        meter.onFrame(t)
        // A pause of a second is no interval.
        t += 1_000_000_000L; meter.onFrame(t)
        for (i in 1..6) { t += i * 1_000_000L; meter.onFrame(t) }
        assertEquals(listOf(3_000_000L, 4_000_000L, 5_000_000L, 6_000_000L), meter.intervals().toList())
        meter.reset()
        assertEquals(0, meter.intervals().size)
    }

    @Test
    fun gammaFollowsBt2390() {
        // γ = 1.2 − 0.076 · log10(L / 5), checked to 0.005 as the report asks.
        assertEquals(1.2, AmbientGamma.gamma(5.0), 1e-12)
        assertEquals(1.2, AmbientGamma.gamma(1.0), 1e-12)
        assertEquals(1.2 - 0.076, AmbientGamma.gamma(50.0), 0.005)
        assertEquals(1.048, AmbientGamma.gamma(500.0), 0.005)
        assertEquals(1.2 - 0.076 * log10(200.0 / 5), AmbientGamma.gamma(200.0), 1e-12)
        // Full sunlight, 100 000 lx -> 6366 cd/m² -> γ 0.964: the formula without any extra limit.
        assertEquals(0.964, AmbientGamma.gamma(AmbientGamma.surroundLuminance(100_000f)), 0.005)
    }

    @Test
    fun luxBecomesSurroundLuminanceOverAMatteRoom() {
        assertEquals(1000 * 0.2 / PI, AmbientGamma.surroundLuminance(1000f), 1e-9)
        // 1000 lx (bright office) -> 63.7 cd/m² -> γ 1.116.
        assertEquals(1.116, AmbientGamma.gamma(AmbientGamma.surroundLuminance(1000f)), 0.005)
        assertEquals(0.0, AmbientGamma.surroundLuminance(-3f), 0.0)
    }

    @Test
    fun smoothingNeverPumps() {
        // A jump from dark to sunlight moves at most 0.01 per second.
        var gamma = 1.2
        for (i in 1..4) {
            val before = gamma
            gamma = AmbientGamma.step(gamma, 0.9, 0.25)
            assertTrue(before - gamma <= 0.01 * 0.25 + 1e-12)
        }
        // Small differences follow the 5 s low-pass: after 5 s about 63 % of the way.
        var g = 1.10
        repeat(20) { g = AmbientGamma.step(g, 1.105, 0.25) }
        assertEquals(1.10 + 0.005 * 0.632, g, 1e-4)
        assertEquals(1f, AmbientGamma.ratio(1.2), 1e-6f)
    }
}
