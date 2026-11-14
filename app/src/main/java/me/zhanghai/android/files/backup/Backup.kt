/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.backup

import androidx.lifecycle.MutableLiveData

sealed class BackupStatus {
    object Idle : BackupStatus()

    /** [progress] is in percent, or -1 while the files are still being counted. */
    class Running(val jobId: Int, val progress: Int, val fileName: String?) : BackupStatus()

    /** [skipped] are the files that could not be read and are missing in the backup. */
    class Done(val fileCount: Int, val size: Long, val skipped: List<String>) : BackupStatus()

    /**
     * [detail] is null when there was nothing to back up. [isLarge] hints at the 4 GB file limit of
     * FAT32 drives. [message] replaces the general explanation when the cause is known.
     */
    class Failed(
        val detail: String?,
        val isLarge: Boolean,
        val message: String? = null
    ) : BackupStatus()
}

/** Outlives the backup screen, so the result is still there when the user comes back. */
object BackupStatusLiveData : MutableLiveData<BackupStatus>(BackupStatus.Idle)

/** Entry names mirror the folders on the device, so a restored backup looks familiar. */
fun backupEntryName(path: String, storageRoot: String): String =
    path.removePrefix("$storageRoot/").removePrefix("/")

/** Keeps two entries from overwriting each other when the backup is unpacked. */
fun uniqueEntryName(name: String, usedNames: MutableSet<String>): String {
    var uniqueName = name
    var number = 2
    while (!usedNames.add(uniqueName)) {
        val fileName = name.substringAfterLast('/')
        val dotIndex = fileName.lastIndexOf('.')
        val cut = if (dotIndex > 0) name.length - fileName.length + dotIndex else name.length
        uniqueName = "${name.substring(0, cut)} ($number)${name.substring(cut)}"
        ++number
    }
    return uniqueName
}

/** A folder inside another selected folder would be written twice. */
fun withoutNestedFolders(folders: List<String>): List<String> =
    folders.distinct().filter { folder -> folders.none { folder.startsWith("$it/") } }
