/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleMediaTest {
    @Test
    fun forgottenRowsAreRecognisedAndPathsAskedOnce() {
        assertEquals(listOf("/a/x.mp3"), StaleMedia.remember(listOf("/a/x.mp3"), listOf(9001L)))
        assertEquals(emptyList<String>(), StaleMedia.remember(listOf("/a/x.mp3"), listOf(9001L)))
        assertTrue(StaleMedia.isForgotten(9001L))
        assertFalse(StaleMedia.isForgotten(9002L))
        assertFalse(StaleMedia.isForgotten(null))
    }
}
