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

/**
 * What a running scan knows so far. [kinds] are bytes per [StorageInsights.KINDS] entry and [folder] is where the
 * walk is, relative to the storage. [compared] is the share of duplicate candidates checked, below 0 while the
 * walk is still running.
 */
class ScanProgress(val files: Int, val bytes: Long, val kinds: LongArray, val folder: String, val compared: Float = -1f)

object StorageAnalysis {
    const val LARGE_FILE_SIZE = 50L * 1024 * 1024
    const val OLD_FILE_DAYS = 180
    private const val PARTIAL_HASH_SIZE = 64 * 1024
    private const val PROGRESS_INTERVAL_NANOS = 50_000_000L

    // Walks the volume with java.io.File; unreadable folders (e.g. Android/data on 11+) are skipped.
    // Progress is reported by the clock, so the screen moves evenly on a fast and on a slow storage.
    fun scan(root: File, isCancelled: () -> Boolean, onProgress: (ScanProgress) -> Unit): List<ScannedFile> {
        val files = mutableListOf<ScannedFile>()
        val trash = File(root, Trash.DIRECTORY_NAME)
        val kinds = LongArray(StorageInsights.KINDS.size)
        var bytes = 0L
        var reported = 0L
        fun report(file: File) = onProgress(
            ScanProgress(files.size, bytes, kinds.clone(), file.parent.orEmpty().removePrefix(root.path).trim('/'))
        )
        root.walkTopDown()
            .onEnter { it != trash && !isCancelled() }
            .filter { it.isFile }
            .forEach {
                val size = it.length()
                files += ScannedFile(it.path, size, it.lastModified())
                bytes += size
                kinds[StorageInsights.KINDS.indexOf(StorageInsights.kindOf(it.path))] += size
                val now = System.nanoTime()
                if (now - reported >= PROGRESS_INTERVAL_NANOS) {
                    reported = now
                    report(it)
                }
            }
        // The last numbers are the real ones, whatever the clock said; a cancelled scan reports nothing more.
        if (!isCancelled()) onProgress(ScanProgress(files.size, bytes, kinds.clone(), ""))
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

    /** What lies directly in [folder]: its subfolders with their total size, and its own files. */
    fun children(folder: String, files: List<ScannedFile>): Pair<List<FolderSize>, List<ScannedFile>> {
        val inside = files.filter { it.path.startsWith("$folder/") }
        return folderSizes(folder, inside) to inside.filter { '/' !in it.path.substring(folder.length + 1) }
    }

    // Size first, then a hash of the first 64 KiB, then a full hash, so only real candidates are read.
    fun duplicates(
        files: List<ScannedFile>,
        partialHash: (ScannedFile) -> String? = { hash(it, PARTIAL_HASH_SIZE.toLong()) },
        fullHash: (ScannedFile) -> String? = { hash(it, Long.MAX_VALUE) },
        isCancelled: () -> Boolean = { false },
        /** Called with the share of same-size groups that are checked, 0 to 1. */
        onProgress: (Float) -> Unit = {}
    ): List<DuplicateGroup> {
        val candidates = files.filter { !it.isHidden && it.size > 0 }
            .groupBy { it.size }.values.filter { it.size > 1 }
        var done = 0
        var reported = 0L
        val groups = candidates
            .flatMap { sameSize ->
                if (isCancelled()) {
                    return@flatMap emptyList()
                }
                val now = System.nanoTime()
                if (now - reported >= PROGRESS_INTERVAL_NANOS) {
                    reported = now
                    onProgress(done.toFloat() / candidates.size)
                }
                done++
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
        if (!isCancelled()) onProgress(1f)
        return groups
    }

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
