/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Drives the real AutoReload on virtual time: loads take [loadMs], counts started and finished loads. */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoReloadTest {
    private class Loads(scope: TestScope, loadMs: Long) {
        var started = 0
        var finished = 0
        val auto = AutoReload(scope, 800, 3000, { scope.testScheduler.currentTime }) {
            started++
            delay(loadMs)
            finished++
        }
    }

    /** Sends a signal every [everyMs] for [count] signals, starting now. */
    private fun TestScope.signals(auto: AutoReload, count: Int, everyMs: Long) = launch {
        repeat(count) { auto.signal(); delay(everyMs) }
    }

    @Test
    fun fiftyFilesInOneSecondLoadOnce() = runTest {
        val loads = Loads(this, 500)
        signals(loads.auto, 50, 20)
        advanceUntilIdle()
        assertEquals(1, loads.started)
        assertEquals(1, loads.finished)
    }

    @Test
    fun aLongCopyNeverCancelsASlowLoad() = runTest {
        // 20 s of changes every 100 ms, a load takes 5 s (a large library): every load that starts finishes.
        val loads = Loads(this, 5000)
        signals(loads.auto, 200, 100)
        advanceUntilIdle()
        assertEquals(loads.started, loads.finished)
        // One load per 5 s while changes keep coming, plus one for the changes during the last load.
        assertEquals(5, loads.started)
    }

    @Test
    fun aLongCopyWithFastLoadsReloadsEveryThreeSeconds() = runTest {
        val loads = Loads(this, 100)
        signals(loads.auto, 100, 100)
        advanceUntilIdle()
        assertEquals(4, loads.started)
        assertEquals(4, loads.finished)
    }

    @Test
    fun anExplicitReloadReplacesTheRunningOne() = runTest {
        val loads = Loads(this, 1000)
        loads.auto.reloadNow()
        testScheduler.advanceTimeBy(500)
        loads.auto.reloadNow()
        advanceUntilIdle()
        assertEquals(2, loads.started)
        assertEquals(1, loads.finished)
    }

    @Test
    fun stopDropsPendingChanges() = runTest {
        val loads = Loads(this, 100)
        signals(loads.auto, 5, 20)
        testScheduler.advanceTimeBy(200)
        loads.auto.stop()
        advanceUntilIdle()
        // All five signals came in the first 80 ms; the hidden screen drops the reload they had scheduled.
        assertEquals(0, loads.started)
    }

    @Test
    fun changesDuringALoadRunItOnceMoreAfterwards() = runTest {
        val loads = Loads(this, 2000)
        loads.auto.reloadNow()
        signals(loads.auto, 3, 100)
        advanceUntilIdle()
        assertEquals(2, loads.started)
        assertEquals(2, loads.finished)
    }
}
