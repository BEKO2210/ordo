/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.backup

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupJobGuardTest {
    @Test
    fun screenShowsOnlyTheSameJob() {
        assertTrue(screenStillShowsJob(7, 7))
        assertFalse(screenStillShowsJob(8, 7))
        assertFalse(screenStillShowsJob(null, 7))
    }

    @Test
    fun cancelLeavesTheRunningJobOnScreen() {
        val fragment = File("src/main/java/me/zhanghai/android/files/backup/BackupFragment.kt")
            .readText()
        val running = fragment.substringAfter("is BackupStatus.Running ->")
            .substringBefore("is BackupStatus.Done ->")
        val checking = fragment.substringAfter("is BackupStatus.RestoreChecking ->")
            .substringBefore("is BackupStatus.RestoreNeedsPassword ->")
        assertFalse(running.contains("BackupStatus.Idle"))
        assertFalse(checking.contains("BackupStatus.Idle"))
        assertTrue(running.contains("cancelledBackupJobId = status.jobId"))
        assertTrue(checking.contains("cancelledRestoreJobId = status.jobId"))
    }

    @Test
    fun finishedWorkPostsOnlyWhileThatJobIsVisible() {
        val jobs = File("src/main/java/me/zhanghai/android/files/filejob/FileJobs.kt").readText()
        val preview = jobs.substringAfter("class RestorePreviewJob")
            .substringBefore("class RestoreBackupJob")
        val previewRun = preview.substringAfter("override fun run()")
            .substringBefore("private fun inspectArchive")
        assertTrue(previewRun.contains("postIfStillChecking"))
        assertFalse(previewRun.contains("BackupStatusLiveData.postValue"))
        val restore = jobs.substringAfter("class RestoreBackupJob")
        val cancelled = restore.substringAfter("if (e.isInterruptedFileJob())")
            .substringBefore("e.printStackTrace()")
        assertTrue(cancelled.contains("restore_error_cancelled"))
        assertTrue(cancelled.contains("BackupStatus.RestoreFailed"))
        assertFalse(cancelled.contains("BackupStatus.Idle"))
        val done = jobs.substringAfter("Os.fsync(fileDescriptor)")
            .substringBefore("} catch (e: Exception)")
        val clearAt = done.indexOf("CutBackup.clear")
        val postedAt = done.indexOf("BackupStatus.Done")
        assertTrue(clearAt >= 0 && postedAt > clearAt)
        assertTrue(jobs.contains("private fun postIfCurrent"))
        assertTrue(jobs.contains("CutBackup.mark"))
    }

    @Test
    fun nextStartDeletesACutBackup() {
        val service = File("src/main/java/me/zhanghai/android/files/filejob/FileJobService.kt")
            .readText()
        val onCreate = service.substringAfter("override fun onCreate()")
            .substringBefore("override fun onBind")
        assertTrue(onCreate.contains("deleteCutBackup()"))
        assertTrue(service.contains("SftpClient.disconnectThread"))
    }
}
