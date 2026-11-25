/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.backup

import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.MutableLiveData
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import me.zhanghai.android.files.app.contentResolver
import me.zhanghai.android.files.provider.content.resolver.Resolver
import me.zhanghai.android.files.provider.content.resolver.ResolverException

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

/**
 * Idle and a newer job both count as having moved on, so a check or backup that finishes after
 * cancel must not publish its result.
 */
internal fun screenStillShowsJob(currentJobId: Int?, jobId: Int): Boolean =
    currentJobId == jobId

/**
 * Removes the file the picker made for a backup that was not written. The picker can also hand out a file that
 * already holds data, such as an older backup; that one is left alone.
 */
fun deleteDocumentIfEmpty(uri: Uri) {
    val size = try {
        Resolver.getSize(uri)
    } catch (e: ResolverException) {
        e.printStackTrace()
        null
    }
    if (size == 0L) {
        try {
            DocumentsContract.deleteDocument(contentResolver, uri)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

/** Entry names mirror the folders on the device, so a restored backup looks familiar. */
fun backupEntryName(path: String, storageRoot: String): String =
    path.removePrefix("$storageRoot/").removePrefix("/")

/**
 * Only names the restore accepts go into a backup, so every backup Ordo writes can be restored, and only vault files
 * go into the vault folder.
 */
fun isRestorableBackupEntry(name: String, isVault: Boolean = false): Boolean =
    try {
        restoreEntryPath(name).isVault == isVault
    } catch (e: IllegalArgumentException) {
        false
    }

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

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
// The paths behind the shared storage that a file descriptor can show (FUSE, its lower file system, SD cards).
private val EMULATED_PATH = Regex("""^/(?:data/media|mnt/(?:user|pass_through|installer|androidwritable)/\d+/emulated)/(\d+)/(.*)$""")
private val MEDIA_RW_PATH = Regex("""^/mnt/media_rw/([^/]+)/(.*)$""")

/**
 * The file paths (as the backup's folder walk spells them, under /storage) that the backup's own target document
 * can be: from the document id of Android's storage provider ("primary:Backups/x.zip") and from the path of an open
 * file descriptor of it. A backup saved into a folder it backs up must leave itself out.
 */
fun backupTargetPaths(authority: String?, documentId: String?, fdPath: String?, storageRoot: String): Set<String> {
    val paths = mutableSetOf<String>()
    if (authority == EXTERNAL_STORAGE_AUTHORITY && documentId != null && ':' in documentId) {
        val root = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':').trim('/')
        val base = if (root == "primary") storageRoot else "/storage/$root"
        if (relative.isNotEmpty()) paths += "${base.trimEnd('/')}/$relative"
    }
    if (fdPath != null && fdPath.startsWith("/")) { // not "pipe:[…]" of a cloud provider
        paths += EMULATED_PATH.matchEntire(fdPath)?.let { "/storage/emulated/${it.groupValues[1]}/${it.groupValues[2]}" }
            ?: MEDIA_RW_PATH.matchEntire(fdPath)?.let { "/storage/${it.groupValues[1]}/${it.groupValues[2]}" }
            ?: fdPath
    }
    return paths
}
