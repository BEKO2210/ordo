/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CutBackupTest {
    @Test
    fun markPeekAndClearRoundTrip() {
        val directory = java.nio.file.Files.createTempDirectory("cut-backup").toFile()
        try {
            assertNull(CutBackup.peek(directory))
            CutBackup.mark(directory, "content://backup/1")
            assertEquals("content://backup/1", CutBackup.peek(directory))
            assertFalse(File(directory, "backup-cut-uri.tmp").exists())
            CutBackup.mark(directory, "content://backup/2")
            assertEquals("content://backup/2", CutBackup.peek(directory))
            CutBackup.clear(directory)
            assertNull(CutBackup.peek(directory))
            assertEquals(emptyList<File>(), directory.listFiles()?.toList())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun blankOrTemporaryMarkerIsAbsent() {
        val directory = java.nio.file.Files.createTempDirectory("cut-backup").toFile()
        try {
            File(directory, "backup-cut-uri").writeText("  \n")
            assertNull(CutBackup.peek(directory))
            File(directory, "backup-cut-uri").delete()
            File(directory, "backup-cut-uri.tmp").writeText("content://backup/half")
            assertNull(CutBackup.peek(directory))
        } finally {
            directory.deleteRecursively()
        }
    }
}
