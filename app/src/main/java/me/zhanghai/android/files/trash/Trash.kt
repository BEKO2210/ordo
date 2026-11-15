/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.trash

import android.os.Environment
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.UUID
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.StandardCopyOption
import me.zhanghai.android.files.provider.common.moveTo
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

class TrashEntry(
    val id: String,
    val root: String,
    val originalPath: String,
    val deletedAt: Long
) {
    val file: File
        get() = File(Trash.filesDir(root), id)

    val infoFile: File
        get() = File(Trash.infoDir(root), id + Trash.INFO_SUFFIX)

    /** Bytes of all files inside; a folder's own length() is only its inode size. */
    val size: Long
        get() = file.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    val name: String
        get() = originalPath.substringAfterLast('/')
}

object Trash {
    const val DIRECTORY_NAME = ".OrdoTrash"
    const val INFO_SUFFIX = ".info"
    private const val KEY_PATH = "path"
    private const val KEY_DELETED_AT = "deletedAt"
    private val PROTECTED_DIRECTORIES =
        listOf(DIRECTORY_NAME, "Android/data", "Android/obb", "Android/media")
    private val ROOT_REGEX = Regex(
        "^(/storage/emulated/\\d+|/storage/(?!emulated/|self/)[^/]+)/.+", RegexOption.DOT_MATCHES_ALL
    )

    val isEnabled: Boolean
        get() = Settings.TRASH_ENABLED.valueCompat

    // Only shared storage volumes get a trash: there a rename inside the volume is always possible.
    fun rootOf(path: String): String? {
        val root = ROOT_REGEX.matchEntire(path)?.groupValues?.get(1) ?: return null
        val relative = path.substring(root.length + 1)
        // App-private folders stay out of the shared trash, so their data never becomes visible.
        // Shared storage is case-insensitive, so "android/DATA" must match too.
        val excluded = PROTECTED_DIRECTORIES.any {
            relative.equals(it, true) || relative.startsWith("$it/", true)
        }
        return if (excluded) null else root
    }

    // Info files live on shared storage, so other apps could forge them; restore only to safe paths.
    fun isValidOriginalPath(path: String, root: String): Boolean =
        path.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." } &&
            rootOf(path) == root

    fun canTrash(path: Path): Boolean = path.isLinuxPath && rootOf(path.toString()) != null

    fun filesDir(root: String): File = File(root, "$DIRECTORY_NAME/files")

    fun infoDir(root: String): File = File(root, "$DIRECTORY_NAME/info")

    // Writes the info file first, so a crash mid-move never leaves a trashed file without origin.
    @Throws(IOException::class)
    private fun prepare(path: Path, now: Long = System.currentTimeMillis()): Path {
        val pathString = path.toString()
        val root = rootOf(pathString) ?: throw IOException("Not trashable: $pathString")
        val filesDir = filesDir(root)
        val infoDir = infoDir(root)
        if (!filesDir.isDirectory && !filesDir.mkdirs() || !infoDir.isDirectory && !infoDir.mkdirs()) {
            throw IOException("Cannot create trash in $root")
        }
        File(root, "$DIRECTORY_NAME/.nomedia").let { if (!it.exists()) it.createNewFile() }
        val id = "$now-${UUID.randomUUID().toString().take(8)}"
        File(infoDir, id + INFO_SUFFIX).outputStream().use {
            formatInfo(pathString, now).store(it, null)
        }
        return Paths.get(File(filesDir, id).path)
    }

    /** Returns where the file now lives in the trash; its file name is the entry id. */
    @Throws(IOException::class)
    fun moveToTrash(path: Path): Path {
        val trashPath = prepare(path)
        try {
            path.moveTo(trashPath, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.ATOMIC_MOVE)
            return trashPath
        } catch (e: IOException) {
            File(infoDir(rootOf(path.toString())!!), trashPath.fileName.toString() + INFO_SUFFIX)
                .delete()
            throw e
        }
    }

    fun formatInfo(originalPath: String, deletedAt: Long): Properties =
        Properties().apply {
            setProperty(KEY_PATH, originalPath)
            setProperty(KEY_DELETED_AT, deletedAt.toString())
        }

    fun parseInfo(id: String, root: String, properties: Properties): TrashEntry? {
        val path = properties.getProperty(KEY_PATH)?.takeIf { isValidOriginalPath(it, root) }
            ?: return null
        val deletedAt = properties.getProperty(KEY_DELETED_AT)?.toLongOrNull() ?: return null
        return TrashEntry(id, root, path, deletedAt)
    }

    @Suppress("DEPRECATION")
    fun roots(): List<String> {
        val roots = mutableListOf(Environment.getExternalStorageDirectory().path)
        File("/storage").listFiles()?.forEach {
            if (it.name != "emulated" && it.name != "self" && it.isDirectory) {
                roots += it.path
            }
        }
        return roots
    }

    fun entries(): List<TrashEntry> =
        roots().flatMap { root ->
            infoDir(root).listFiles()?.mapNotNull { infoFile ->
                if (!infoFile.name.endsWith(INFO_SUFFIX)) {
                    return@mapNotNull null
                }
                val id = infoFile.name.removeSuffix(INFO_SUFFIX)
                val properties = Properties()
                try {
                    infoFile.inputStream().use { properties.load(it) }
                } catch (e: IOException) {
                    e.printStackTrace()
                    return@mapNotNull null
                }
                parseInfo(id, root, properties)?.takeIf { it.file.exists() }
            } ?: emptyList()
        }.sortedByDescending { it.deletedAt }

    fun restoreTarget(originalPath: String, exists: (String) -> Boolean): String {
        if (!exists(originalPath)) {
            return originalPath
        }
        val directory = originalPath.substringBeforeLast('/')
        val name = originalPath.substringAfterLast('/')
        val dotIndex = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val baseName = name.substring(0, dotIndex)
        val extension = name.substring(dotIndex)
        var number = 1
        while (true) {
            val candidate = "$directory/$baseName ($number)$extension"
            if (!exists(candidate)) {
                return candidate
            }
            ++number
        }
    }

    fun isExpired(entry: TrashEntry, retentionDays: Int, now: Long): Boolean =
        retentionDays > 0 && now - entry.deletedAt > retentionDays * 24L * 60 * 60 * 1000

    // Runs on app start in background; deletes entries older than the retention setting.
    fun purgeExpired(now: Long = System.currentTimeMillis()) {
        val retentionDays = Settings.TRASH_RETENTION_DAYS.valueCompat.toIntOrNull() ?: return
        for (entry in entries()) {
            if (isExpired(entry, retentionDays, now)) {
                if (entry.file.deleteRecursively()) {
                    entry.infoFile.delete()
                }
            }
        }
    }
}
