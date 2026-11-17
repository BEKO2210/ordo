/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayableAudioTest {
    @Test
    fun commonFormatsGoToThePlayer() {
        listOf("audio/mpeg", "audio/mp4", "audio/ogg", "audio/flac", "audio/x-wav", "AUDIO/MPEG; charset=x")
            .forEach { assertTrue(it, isPlayableAudioMimeType(it)) }
    }

    @Test
    fun playlistsMidiAndNonAudioStayWithOtherApps() {
        listOf("audio/midi", "audio/x-mpegurl", "audio/mpegurl", "audio/x-ms-wma", "video/mp4", "")
            .forEach { assertFalse(it, isPlayableAudioMimeType(it)) }
    }
}
