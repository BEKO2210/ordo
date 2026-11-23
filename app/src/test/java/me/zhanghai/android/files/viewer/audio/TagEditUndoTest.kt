/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagEditUndoTest {
    @Test
    fun theDeadlineIsWallClockSoItSurvivesAProcessRestart() {
        assertEquals(4_000L, TagEditUndo.remainingMs(deadlineMs = 14_000L, nowMs = 10_000L))
        assertTrue(TagEditUndo.remainingMs(deadlineMs = 14_000L, nowMs = 15_000L) <= 0)
    }
}
