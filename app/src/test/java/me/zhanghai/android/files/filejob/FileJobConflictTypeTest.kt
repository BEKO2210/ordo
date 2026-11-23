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

    @Test
    fun fileOverFolderConflictOffersRenameNotReplace() {
        val actions = FileJobConflictDialogFragment.availableActions(false, true)
        assertTrue(actions.contains(FileJobConflictAction.RENAME))
        assertTrue(actions.contains(FileJobConflictAction.SKIP))
        assertTrue(actions.contains(FileJobConflictAction.CANCEL))
        assertFalse(actions.contains(FileJobConflictAction.MERGE_OR_REPLACE))
    }

    @Test
    fun folderOverFileConflictOffersRenameNotReplace() {
        val actions = FileJobConflictDialogFragment.availableActions(true, false)
        assertTrue(actions.contains(FileJobConflictAction.RENAME))
        assertFalse(actions.contains(FileJobConflictAction.MERGE_OR_REPLACE))
    }

    @Test
    fun fileOverFileConflictOffersReplaceAndRename() {
        val actions = FileJobConflictDialogFragment.availableActions(false, false)
        assertTrue(actions.contains(FileJobConflictAction.RENAME))
        assertTrue(actions.contains(FileJobConflictAction.MERGE_OR_REPLACE))
    }

    @Test
    fun folderOverFolderConflictOffersMergeAndRename() {
        val actions = FileJobConflictDialogFragment.availableActions(true, true)
        assertTrue(actions.contains(FileJobConflictAction.RENAME))
        assertTrue(actions.contains(FileJobConflictAction.MERGE_OR_REPLACE))
    }
}
