/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.analysis

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import me.zhanghai.android.files.trash.Trash

class ScannedFile(val path: String, val size: Long, val lastModified: Long) {
    val name: String
        get() = path.substringAfterLast('/')

    // Hidden files are mostly app caches and indexes; never suggest them for deletion.
    val isHidden: Boolean
        get() = path.contains("/.")
}

class DuplicateGroup(val files: List<ScannedFile>) {
    val wastedSize: Long
        get() = files.drop(1).sumOf { it.size }
}

class FolderSize(val path: String, val size: Long)

object StorageAnalysis {
    const val LARGE_FILE_SIZE = 50L * 1024 * 1024
    const val OLD_FILE_DAYS = 180
    private const val PARTIAL_HASH_SIZE = 64 * 1024

    // Walks the volume with java.io.File; unreadable folders (e.g. Android/data on 11+) are skipped.
    fun scan(root: File, isCancelled: () -> Boolean, onProgress: (Int) -> Unit): List<ScannedFile> {
        val files = mutableListOf<ScannedFile>()
        val trash = File(root, Trash.DIRECTORY_NAME)
        root.walkTopDown()
            .onEnter { it != trash && !isCancelled() }
            .filter { it.isFile }
            .forEach {
                files += ScannedFile(it.path, it.length(), it.lastModified())
                if (files.size % 500 == 0) {
                    onProgress(files.size)
                }
            }
        return files
    }

    fun largeFiles(files: List<ScannedFile>, minSize: Long = LARGE_FILE_SIZE): List<ScannedFile> =
        files.filter { !it.isHidden && it.size >= minSize }.sortedByDescending { it.size }

    fun oldFiles(
        files: List<ScannedFile>,
        now: Long,
        days: Int = OLD_FILE_DAYS
    ): List<ScannedFile> {
        val limit = now - days * 24L * 60 * 60 * 1000
        return files.filter { !it.isHidden && it.lastModified in 1 until limit }.sortedByDescending { it.size }
    }

    fun apkFiles(files: List<ScannedFile>): List<ScannedFile> =
        files.filter { !it.isHidden && it.name.endsWith(".apk", true) }.sortedByDescending { it.size }

    fun folderSizes(root: String, files: List<ScannedFile>): List<FolderSize> =
        files.groupBy { it.path.removePrefix("$root/").substringBefore('/', "") }
            .filterKeys { it.isNotEmpty() }
            .map { (name, files) -> FolderSize("$root/$name", files.sumOf { it.size }) }
            .sortedByDescending { it.size }

    // Size first, then a hash of the first 64 KiB, then a full hash, so only real candidates are read.
    fun duplicates(
        files: List<ScannedFile>,
        partialHash: (ScannedFile) -> String? = { hash(it, PARTIAL_HASH_SIZE.toLong()) },
        fullHash: (ScannedFile) -> String? = { hash(it, Long.MAX_VALUE) },
        isCancelled: () -> Boolean = { false }
    ): List<DuplicateGroup> =
        files.filter { !it.isHidden && it.size > 0 }
            .groupBy { it.size }.values.filter { it.size > 1 }
            .flatMap { sameSize ->
                if (isCancelled()) {
                    return@flatMap emptyList()
                }
                sameSize.groupByNotNull(partialHash).values.filter { it.size > 1 }
                    .flatMap { candidates ->
                        if (candidates.first().size <= PARTIAL_HASH_SIZE) {
                            listOf(candidates)
                        } else {
                            candidates.groupByNotNull(fullHash).values.filter { it.size > 1 }
                        }
                    }
            }
            .map { group -> DuplicateGroup(group.sortedByDescending { it.lastModified }) }
            .sortedByDescending { it.wastedSize }

    fun allButNewest(groups: List<DuplicateGroup>): List<ScannedFile> =
        groups.flatMap { it.files.drop(1) }

    private fun <K> List<ScannedFile>.groupByNotNull(
        key: (ScannedFile) -> K?
    ): Map<K, List<ScannedFile>> {
        val map = LinkedHashMap<K, MutableList<ScannedFile>>()
        for (file in this) {
            val k = key(file) ?: continue
            map.getOrPut(k) { mutableListOf() } += file
        }
        return map
    }

    private fun hash(file: ScannedFile, limit: Long): String? =
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            File(file.path).inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var remaining = limit
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) {
                        break
                    }
                    digest.update(buffer, 0, read)
                    remaining -= read
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: IOException) {
            null
        }
}
