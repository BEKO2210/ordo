/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.backup

import android.net.Uri
import androidx.lifecycle.MutableLiveData
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

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

    class RestoreChecking(val jobId: Int, val source: Uri) : BackupStatus()

    class RestoreNeedsPassword(val source: Uri) : BackupStatus()

    /** [password] stays only in process memory until the user confirms or leaves this preview. */
    class RestorePreview(
        val source: Uri,
        val password: String?,
        val fileCount: Int,
        val size: Long,
        val vaultFileCount: Int
    ) : BackupStatus()

    /** [progress] is in percent; [fileName] is null before the first normal file is read. */
    class RestoreRunning(val jobId: Int, val progress: Int, val fileName: String?) : BackupStatus()

    class RestoreDone(
        val restored: Int,
        val skipped: Int,
        val failed: Int,
        val vaultSkipped: Int
    ) : BackupStatus()

    class RestoreFailed(
        val source: Uri?,
        val passwordRequired: Boolean,
        val message: String,
        val restored: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val vaultSkipped: Int = 0
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

/** A file that failed while it was read into the backup. Its entry is already started, so the backup stops. */
class BackupSourceException(val fileName: String, cause: IOException) : IOException(cause)

/**
 * Marks read failures, so an unreadable file can be told apart from a failing target. Stops at the size announced
 * in the archive, which also keeps a backup saved into a folder it backs up from reading itself forever. A file that
 * ends before that size fails too, as its entry would be cut short.
 */
class BackupSourceStream(
    private val inputStream: InputStream,
    private var remainingSize: Long,
    private val fileName: String
) : InputStream() {
    @Throws(IOException::class)
    override fun read(): Int {
        val buffer = ByteArray(1)
        return if (read(buffer, 0, 1) == -1) -1 else buffer[0].toInt() and 0xFF
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remainingSize <= 0) {
            return -1
        }
        val size = try {
            inputStream.read(buffer, offset, minOf(length.toLong(), remainingSize).toInt())
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            throw BackupSourceException(fileName, e)
        }
        if (size < 0) {
            throw BackupSourceException(fileName, EOFException("$remainingSize bytes missing"))
        }
        remainingSize -= size
        return size
    }

    @Throws(IOException::class)
    override fun close() {
        inputStream.close()
    }
}
