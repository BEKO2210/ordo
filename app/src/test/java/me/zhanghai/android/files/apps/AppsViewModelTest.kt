/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AppsViewModelTest {
    @Test
    fun backupFileName() {
        assertEquals("Maps 1.2", AppsViewModel.backupFileName("Maps", "1.2"))
        assertEquals("Maps", AppsViewModel.backupFileName("Maps", null))
        assertEquals("a_b_c_d 1_2", AppsViewModel.backupFileName("a/b:c*d", "1/2"))
        assertEquals("app", AppsViewModel.backupFileName(" ", ""))
        assertEquals("app", AppsViewModel.backupFileName("..", null))
        assertEquals("hidden", AppsViewModel.backupFileName(".hidden", null))
    }
}
