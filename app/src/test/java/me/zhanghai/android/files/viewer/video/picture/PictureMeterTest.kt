/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureMeterTest {
    @Test
    fun takeSumsASecondAndStartsOver() {
        val meter = PictureMeter()
        meter.addPass(PictureMeter.PASS_DETAIL, 4.0)
        meter.addPass(PictureMeter.PASS_DETAIL, 6.0)
        meter.addAudio(250_000)
        meter.addAudio(750_000)
        val (passes, audio) = meter.take()
        val detail = passes.getValue(PictureMeter.PASS_DETAIL)
        assertEquals(5.0, detail.mean, 1e-9)
        assertEquals(6.0, detail.max, 1e-9)
        assertEquals(2, detail.count)
        assertEquals(0.5, audio.mean, 1e-9)
        assertEquals(0.75, audio.max, 1e-9)
        val (empty, silent) = meter.take()
        assertTrue(empty.isEmpty())
        assertEquals(0, silent.count)
    }

    @Test
    fun csvHasOneRowPerSecondWithDotsAndEmptyUnknowns() {
        val meter = PictureMeter().apply { timer = "gpu" }
        meter.addPass(PictureMeter.PASS_LANCZOS, 1.25)
        val (passes, audio) = meter.take()
        meter.addRow(3, "film", passes, dropped = 1, headroom = Float.NaN, audio = audio, underruns = 0)
        val lines = meter.csv().trimEnd().lines()
        assertEquals(PictureMeter.HEADER, lines[0])
        assertEquals("3,gpu,film,,,0,1.250,1.250,1,,,0,1,,,,0,0", lines[1])
        assertEquals(lines[0].split(",").size, lines[1].split(",").size)
        meter.clear()
        assertEquals(1, meter.csv().trimEnd().lines().size)
    }

    @Test
    fun signalPathLeavesAccessibilityAQuietSecond() {
        // A tick sends the text change, then the row's description 100 ms later (Android's recurring-event interval);
        // `uiautomator dump` waits for 1 s without events.
        assertTrue(VideoPicture.SIGNAL_REFRESH_MS - 100 >= 1_000)
    }
}
