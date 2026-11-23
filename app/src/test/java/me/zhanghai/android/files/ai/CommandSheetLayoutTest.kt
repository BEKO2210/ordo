/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandSheetLayoutTest {
    @Test
    fun aShortLandscapePhoneCompactsTheSetupOrbAndHidesTheChecks() {
        assertTrue(commandSetupCompact(309))
        assertEquals(55, commandSetupOrbDp(309))
        assertTrue(commandSetupCompact(479))
        assertFalse(commandSetupCompact(480))
        assertEquals(144, commandSetupOrbDp(480))
        assertEquals(144, commandSetupOrbDp(800))
    }
}
