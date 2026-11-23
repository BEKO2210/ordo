/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.filejob

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileJobsCopySafetyTest {
    private val source = File("src/main/java/me/zhanghai/android/files/filejob/FileJobs.kt").readText()

    @Test
    fun archiveWritesToAPartFileFirst() {
        val archive = source.substringAfter("class ArchiveFileJob").substringBefore("class CopyFileJob")
        assertTrue(archive.contains("uniquePartFile"))
        assertTrue(archive.contains("part.moveTo(archiveFile)"))
        assertTrue(archive.contains("part.deleteIfExists()"))
        assertFalse(archive.contains("archiveFile.newByteChannel"))
    }

    @Test
    fun vaultRestoreUsesAUniquePartFile() {
        val restore = source.substringAfter("class RestoreVaultJob").substringBefore("class RestorePreviewJob")
        assertTrue(restore.contains("createTempFile(\".ordo-vault-\""))
        assertFalse(restore.contains("TRUNCATE_EXISTING"))
        assertFalse(restore.contains(".ordo-part\""))
    }

    @Test
    fun moveToVaultMarksTheOriginalUntilDeleted() {
        val move = source.substringAfter("class MoveToVaultJob").substringBefore("class RestoreVaultJob")
        assertTrue(move.contains("markSourcePendingDelete"))
        assertTrue(move.contains("clearSourcePendingDelete"))
    }
}
