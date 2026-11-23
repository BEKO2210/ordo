/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.filejob

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileJobConflictTypeTest {
    @Test
    fun fileOverFileCanBeReplaced() {
        assertTrue(FileJobConflictDialogFragment.isReplaceable(false, false))
    }

    @Test
    fun folderOverFolderIsNotAReplace() {
        assertFalse(FileJobConflictDialogFragment.isReplaceable(true, true))
    }

    @Test
    fun fileOverFolderCannotBeReplaced() {
        assertFalse(FileJobConflictDialogFragment.isReplaceable(false, true))
    }

    @Test
    fun folderOverFileCannotBeReplaced() {
        assertFalse(FileJobConflictDialogFragment.isReplaceable(true, false))
    }
}
