/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackNavigationTest {
    private val start = "/storage/emulated/0/Pictures"
    private val sub = "$start/e2e-sub"

    @Test
    fun inSubfolderOfEntryFolderBackWalksUp() = assertTrue(shouldBackNavigateUp(true, sub, start))

    @Test
    fun atEntryFolderBackLeaves() = assertFalse(shouldBackNavigateUp(true, start, start))

    @Test
    fun withoutEntryFolderBackWalksUpWhilePossible() {
        assertTrue(shouldBackNavigateUp(true, sub, null))
        assertFalse(shouldBackNavigateUp(false, sub, null))
    }
}
