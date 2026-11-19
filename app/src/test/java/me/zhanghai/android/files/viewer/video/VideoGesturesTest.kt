/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

import me.zhanghai.android.files.viewer.video.VideoGestures.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoGesturesTest {
    private fun gestures(x: Float = 200f, y: Float = 500f, allowed: Boolean = true) =
        VideoGestures(10f).apply { down(x, y, allowed) }

    @Test
    fun nothingStartsBelowTwiceTheSlop() {
        assertEquals(Mode.PENDING, gestures().move(200f, 481f, 1000))
        assertEquals(Mode.PENDING, gestures().move(219f, 500f, 1000))
    }

    @Test
    fun aClearAxisPicksTheGestureAndTheHalfThePicksItsLevel() {
        assertEquals(Mode.BRIGHTNESS, gestures(x = 200f).move(205f, 470f, 1000))
        assertEquals(Mode.VOLUME, gestures(x = 800f).move(805f, 470f, 1000))
        assertEquals(Mode.SEEK, gestures().move(240f, 505f, 1000))
    }

    @Test
    fun aDiagonalStaysUndecidedAndADecisionStays() {
        assertEquals(Mode.PENDING, gestures().move(230f, 470f, 1000))
        val decided = gestures()
        decided.move(240f, 500f, 1000)
        assertEquals(Mode.SEEK, decided.move(240f, 300f, 1000))
    }

    @Test
    fun aTouchInTheSystemAreasNeverBecomesAGesture() {
        assertEquals(Mode.NONE, gestures(allowed = false).move(200f, 300f, 1000))
    }

    @Test
    fun seekSpansTwoAndAHalfMinutesAndStaysInTheVideo() {
        assertEquals(75_000L + 60_000L, VideoGestures.seekTarget(60_000L, 500f, 1000, 600_000L))
        assertEquals(0L, VideoGestures.seekTarget(10_000L, -500f, 1000, 600_000L))
        assertEquals(100_000L, VideoGestures.seekTarget(90_000L, 500f, 1000, 100_000L))
        assertTrue(VideoGestures.isCancelled(15f, 10f))
        assertFalse(VideoGestures.isCancelled(-25f, 10f))
    }

    @Test
    fun levelsFollowTheFingerAndStopAtTheEnds() {
        assertEquals(0.75f, VideoGestures.levelAfter(0.5f, -150f, 1000), 0.001f)
        assertEquals(1f, VideoGestures.levelAfter(0.9f, -900f, 1000), 0f)
        assertEquals(0f, VideoGestures.levelAfter(0.1f, 900f, 1000), 0f)
    }
}
