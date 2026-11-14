/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.filejob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferEstimateTest {
    @Test
    fun noEstimateTooEarlyOrWithoutProgress() {
        assertNull(estimateTransfer(100, 1000, 999))
        assertNull(estimateTransfer(0, 1000, 5000))
    }

    @Test
    fun speedAndRemaining() {
        val estimate = estimateTransfer(10_000_000, 30_000_000, 2000)!!
        assertEquals(5_000_000, estimate.bytesPerSecond)
        assertEquals(4, estimate.remainingSeconds)
    }

    @Test
    fun remainingRoundsUpAndNeverNegative() {
        assertEquals(1, estimateTransfer(1000, 1001, 1000)!!.remainingSeconds)
        assertEquals(0, estimateTransfer(2000, 1000, 1000)!!.remainingSeconds)
    }
}
