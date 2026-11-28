/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MusicStoreTest {
    @Test
    fun beforeAndroid10SelectsMusicAndPodcasts() {
        val expected = "(is_music != 0 OR is_podcast != 0)"
        assertEquals(expected, musicSelection(24))
        assertEquals(expected, musicSelection(28))
    }

    @Test
    fun android10SelectsMusicAudiobooksAndPodcasts() {
        assertEquals("(is_music != 0 OR is_audiobook != 0 OR is_podcast != 0)", musicSelection(29))
    }

    @Test
    fun otherAudioCategoriesStayExcluded() {
        val selection = musicSelection(Int.MAX_VALUE)
        listOf("is_ringtone", "is_notification", "is_alarm", "is_recording").forEach {
            assertFalse(selection.contains(it))
        }
    }
}
