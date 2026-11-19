/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPrefsTest {
    @Test
    fun jumpIsOneOfTheOfferedStepsElseTen() {
        assertEquals(5, VideoPrefs.jumpSeconds("5"))
        assertEquals(30, VideoPrefs.jumpSeconds("30"))
        assertEquals(10, VideoPrefs.jumpSeconds("7"))
        assertEquals(10, VideoPrefs.jumpSeconds(null))
    }

    @Test
    fun resumeSortAndOrientationFallBackToTheirDefaults() {
        assertEquals(VideoPrefs.Resume.ALWAYS, VideoPrefs.resume("always"))
        assertEquals(VideoPrefs.Resume.NEVER, VideoPrefs.resume("NEVER"))
        assertEquals(VideoPrefs.Resume.ASK, VideoPrefs.resume("sometimes"))
        assertEquals(VideoPrefs.Sort.LENGTH, VideoPrefs.sort("length"))
        assertEquals(VideoPrefs.Sort.NEWEST, VideoPrefs.sort(null))
        assertEquals(VideoPrefs.Orientation.LANDSCAPE, VideoPrefs.orientation("landscape"))
        assertEquals(VideoPrefs.Orientation.VIDEO, VideoPrefs.orientation("x"))
    }

    @Test
    fun speedsAreTheOfferedOnes() {
        assertEquals(1.25f, VideoPrefs.speed("1.25"), 0f)
        assertEquals(1f, VideoPrefs.speed("4"), 0f)
        assertEquals(3f, VideoPrefs.holdSpeed("3"), 0f)
        assertEquals(2f, VideoPrefs.holdSpeed(""), 0f)
    }

    @Test
    fun endModeIsTheRepeatButtonsSetting() {
        // The settings page stores the same names the repeat button cycles through.
        assertEquals(VideoRepeatMode.NEXT, VideoRepeatMode.decode("NEXT"))
        assertEquals(VideoRepeatMode.STOP, VideoRepeatMode.decode(null))
    }
}
