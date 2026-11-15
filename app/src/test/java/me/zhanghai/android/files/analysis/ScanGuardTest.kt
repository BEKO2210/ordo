/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanGuardTest {
    @Test
    fun onlyNewestScanIsCurrent() {
        val guard = ScanGuard()
        val old = guard.next()
        assertTrue(guard.isCurrent(old))
        val new = guard.next()
        assertFalse(guard.isCurrent(old))
        assertTrue(guard.isCurrent(new))
    }
}
