/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoBackgroundRulesTest {
    @Test
    fun onlySoundOnlyKeepsAPlayingVideoGoing() {
        assertTrue(VideoBackgroundRules.keepsPlaying("audio", playing = true, wasInPip = false, finishing = false))
        assertFalse(VideoBackgroundRules.keepsPlaying("off", playing = true, wasInPip = false, finishing = false))
        assertFalse(VideoBackgroundRules.keepsPlaying("audio", playing = false, wasInPip = false, finishing = false))
        // Closing the picture-in-picture window or the player pauses, whatever the setting.
        assertFalse(VideoBackgroundRules.keepsPlaying("audio", playing = true, wasInPip = true, finishing = false))
        assertFalse(VideoBackgroundRules.keepsPlaying("audio", playing = true, wasInPip = false, finishing = true))
    }

    @Test
    fun pipNeedsSettingDeviceAndPlayback() {
        assertTrue(VideoBackgroundRules.entersPip(enabled = true, supported = true, playing = true))
        assertFalse(VideoBackgroundRules.entersPip(enabled = false, supported = true, playing = true))
        assertFalse(VideoBackgroundRules.entersPip(enabled = true, supported = false, playing = true))
        assertFalse(VideoBackgroundRules.entersPip(enabled = true, supported = true, playing = false))
    }

    @Test
    fun pipAspectFollowsTheVideoWithinAndroidsLimits() {
        assertEquals(1920 to 1080, VideoBackgroundRules.pipAspect(1920, 1080, 1f))
        assertEquals(1080 to 1920, VideoBackgroundRules.pipAspect(1080, 1920, 1f))
        // Anamorphic DVD: 720 × 576 with wide pixels shows as 1024 × 576.
        assertEquals(1024 to 576, VideoBackgroundRules.pipAspect(720, 576, 64f / 45f))
        assertEquals(239 to 100, VideoBackgroundRules.pipAspect(3000, 1000, 1f))
        assertEquals(100 to 239, VideoBackgroundRules.pipAspect(400, 1000, 1f))
        assertEquals(640 to 480, VideoBackgroundRules.pipAspect(640, 480, Float.NaN))
        assertNull(VideoBackgroundRules.pipAspect(0, 0, 1f))
    }
}
