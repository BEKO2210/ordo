/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import org.junit.Assert.assertEquals
import org.junit.Test

class ReloadDebounceTest {
    /** Plays signals at the given times like HomeViewModel does (a new signal replaces the pending reload). */
    private fun reloadsAt(signals: List<Long>, quiet: Long = 800, maxWait: Long = 3000): List<Long> {
        val debounce = ReloadDebounce(quiet, maxWait)
        val reloads = mutableListOf<Long>()
        var pendingAt = -1L
        for (t in signals) {
            if (pendingAt in 0..t) {
                reloads += pendingAt
                debounce.reset()
            }
            pendingAt = t + debounce.delayFor(t)
        }
        if (pendingAt >= 0) reloads += pendingAt
        return reloads
    }

    @Test
    fun fiftyFilesInOneSecondReloadOnce() {
        assertEquals(listOf(980L + 800L), reloadsAt((0 until 50).map { it * 20L }))
    }

    @Test
    fun aLongCopyStillReloadsEveryThreeSeconds() {
        // 10 s of a change every 100 ms: about one reload per 3 s, not one per change.
        val reloads = reloadsAt((0 until 100).map { it * 100L })
        assertEquals(listOf(3000L, 6000L, 9000L, 10700L), reloads)
    }

    @Test
    fun separateChangesEachReload() {
        assertEquals(listOf(800L, 5800L), reloadsAt(listOf(0L, 5000L)))
    }
}
