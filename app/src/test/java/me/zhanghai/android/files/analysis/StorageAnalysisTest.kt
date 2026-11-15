/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.analysis

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAnalysisTest {
    private val day = 24L * 60 * 60 * 1000

    private fun f(path: String, size: Long, modified: Long = 1) = ScannedFile(path, size, modified)

    @Test
    fun largeFiles_thresholdAndOrder() {
        val files = listOf(f("/r/a", 10), f("/r/b", 100), f("/r/c", 50))
        assertEquals(listOf("/r/b", "/r/c"), StorageAnalysis.largeFiles(files, 50).map { it.path })
    }

    @Test
    fun oldFiles_ignoresUnknownDate() {
        val now = 1000 * day
        val files = listOf(f("/r/new", 5, now - day), f("/r/old", 9, now - 200 * day), f("/r/x", 7, 0))
        assertEquals(listOf("/r/old"), StorageAnalysis.oldFiles(files, now).map { it.path })
    }

    @Test
    fun apkFiles_caseInsensitive() {
        val files = listOf(f("/r/a.APK", 1), f("/r/b.apks.txt", 1), f("/r/c.apk", 2))
        assertEquals(listOf("/r/c.apk", "/r/a.APK"), StorageAnalysis.apkFiles(files).map { it.path })
    }

    @Test
    fun children_subfoldersAndOwnFiles() {
        val files = listOf(f("/r/A/x", 1), f("/r/A/B/y", 2), f("/r/A/B/C/z", 4), f("/r/AB/w", 8), f("/r/top", 16))
        val (folders, own) = StorageAnalysis.children("/r/A", files)
        assertEquals(listOf("/r/A/B" to 6L), folders.map { it.path to it.size })
        assertEquals(listOf("/r/A/x"), own.map { it.path })
    }

    @Test
    fun folderSizes_topLevel() {
        val files = listOf(f("/r/A/x", 1), f("/r/A/B/y", 2), f("/r/C/z", 10), f("/r/rootfile", 5))
        val sizes = StorageAnalysis.folderSizes("/r", files)
        assertEquals(listOf("/r/C" to 10L, "/r/A" to 3L), sizes.map { it.path to it.size })
    }

    @Test
    fun duplicates_usesSizeThenHashes() {
        val hashed = mutableListOf<String>()
        val files = listOf(
            f("/r/a", 100, 3), f("/r/b", 100, 5), f("/r/c", 100, 1), f("/r/d", 7), f("/r/e", 0),
            f("/r/f", 0)
        )
        val content = mapOf("/r/a" to "X", "/r/b" to "X", "/r/c" to "Y")
        val groups = StorageAnalysis.duplicates(
            files, { hashed += it.path; content[it.path] }, { content[it.path] }
        )
        assertEquals(1, groups.size)
        assertEquals(listOf("/r/b", "/r/a"), groups[0].files.map { it.path })
        assertEquals(100L, groups[0].wastedSize)
        assertEquals(listOf("/r/a"), StorageAnalysis.allButNewest(groups).map { it.path })
        assertTrue("unique sizes and empty files are never read", hashed.none { it in listOf("/r/d", "/r/e", "/r/f") })
    }

    @Test
    fun duplicates_realFiles() {
        val dir = Files.createTempDirectory("dup").toFile()
        val big = ByteArray(200 * 1024) { (it % 251).toByte() }
        File(dir, "a").writeBytes(big)
        File(dir, "b").writeBytes(big)
        File(dir, "c").writeBytes(big.copyOf().also { it[it.size - 1] = 1 })
        val files = StorageAnalysis.scan(dir, { false }, {})
        val groups = StorageAnalysis.duplicates(files)
        assertEquals(1, groups.size)
        assertEquals(setOf("a", "b"), groups[0].files.map { it.name }.toSet())
        dir.deleteRecursively()
    }

    @Test
    fun scan_lastProgressHasTheRealTotalsByKind() {
        val dir = Files.createTempDirectory("scan").toFile()
        File(dir, "DCIM").mkdirs()
        File(dir, "DCIM/a.jpg").writeBytes(ByteArray(300))
        File(dir, "DCIM/b.mp4").writeBytes(ByteArray(500))
        File(dir, "notes.xyz").writeBytes(ByteArray(20))
        var last: ScanProgress? = null
        val files = StorageAnalysis.scan(dir, { false }) { last = it }
        assertEquals(3, last!!.files)
        assertEquals(820L, last!!.bytes)
        assertEquals(files.sumOf { it.size }, last!!.kinds.sum())
        assertEquals(300L, last!!.kinds[StorageInsights.KINDS.indexOf("image")])
        assertEquals(500L, last!!.kinds[StorageInsights.KINDS.indexOf("video")])
        assertEquals(20L, last!!.kinds[StorageInsights.KINDS.indexOf("other")])
        dir.deleteRecursively()
    }

    @Test
    fun duplicates_progressRunsFromZeroTowardsOne() {
        val files = listOf(f("/r/a", 10), f("/r/b", 10), f("/r/c", 20), f("/r/d", 20), f("/r/e", 30))
        val seen = mutableListOf<Float>()
        StorageAnalysis.duplicates(files, { it.path }, { it.path }, onProgress = { seen += it })
        assertEquals(listOf(0f, 0.5f), seen)
    }

    @Test
    fun hiddenFilesAreNeverCandidates() {
        val files = listOf(
            f("/r/.thumbnails/a", 100, 1), f("/r/Music/.thumbnails/a", 100, 1),
            f("/r/.big.apk", 1L shl 40, 1)
        )
        assertEquals(0, StorageAnalysis.duplicates(files, { "x" }, { "x" }).size)
        assertEquals(0, StorageAnalysis.largeFiles(files, 1).size)
        assertEquals(0, StorageAnalysis.oldFiles(files, 1000 * day).size)
        assertEquals(0, StorageAnalysis.apkFiles(files).size)
        assertEquals(
            setOf("/r/.thumbnails", "/r/Music"),
            StorageAnalysis.folderSizes("/r", files).map { it.path }.toSet()
        )
    }
}
