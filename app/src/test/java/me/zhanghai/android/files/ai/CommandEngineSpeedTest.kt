/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandEngineSpeedTest {
    @Test
    fun turboUsesEveryFastCoreUpToSix() {
        // Tab S9 Ultra (Snapdragon 8 Gen 2): 5 fast cores, 3 little ones.
        assertEquals(5, CommandEngine.threadsFor("turbo", 5, 8))
        // Pixel 11 Pro XL (Tensor G5): 6 fast cores, 2 little ones.
        assertEquals(6, CommandEngine.threadsFor("turbo", 6, 8))
        assertEquals(6, CommandEngine.threadsFor("turbo", 7, 10))
    }

    @Test
    fun balancedKeepsTheEarlierFourThreads() {
        assertEquals(4, CommandEngine.threadsFor("balanced", 5, 8))
        assertEquals(4, CommandEngine.threadsFor("balanced", 4, 8))
        assertEquals(3, CommandEngine.threadsFor("balanced", 3, 6))
    }

    @Test
    fun gentleUsesTwoThreads() {
        assertEquals(2, CommandEngine.threadsFor("eco", 5, 8))
        assertEquals(1, CommandEngine.threadsFor("eco", 1, 2))
    }

    @Test
    fun unknownClocksFallBackToHalfTheCores() {
        assertEquals(4, CommandEngine.threadsFor("turbo", 0, 8))
        assertEquals(2, CommandEngine.threadsFor("balanced", 0, 4))
        assertEquals(1, CommandEngine.threadsFor("balanced", 0, 1))
        // All cores report the same clock: no fast cluster to pick.
        assertEquals(4, CommandEngine.threadsFor("balanced", 8, 8))
    }

    @Test
    fun idleTimeFollowsSpeed() {
        assertEquals(10L, CommandEngine.idleMinutes("turbo"))
        assertEquals(3L, CommandEngine.idleMinutes("balanced"))
        assertEquals(1L, CommandEngine.idleMinutes("eco"))
    }
}
