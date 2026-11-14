/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.analysis

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class StorageInsightsTest {
    private val day = 86_400_000L
    private val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 15, 12, 0) }.timeInMillis
    private fun file(path: String, size: Long, ageDays: Int = 1) = ScannedFile(path, size, now - ageDays * day)

    private fun insights(files: List<ScannedFile>, total: Long = 10_000, free: Long = 4_000, trash: Long = 0): StorageInsights {
        val result = AnalysisResult(
            "/r", StorageAnalysis.largeFiles(files, 500), StorageAnalysis.duplicates(files) { false },
            StorageAnalysis.oldFiles(files, now), StorageAnalysis.apkFiles(files), emptyList(), files
        )
        return StorageInsights.compute("/r", files, result, total, free, trash, now)
    }

    @Test
    fun usageByKind_restIsSystem() {
        val i = insights(listOf(file("/r/a.jpg", 1_000), file("/r/b.mp3", 500), file("/r/c.pdf", 200),
            file("/r/d.mp4", 2_000), file("/r/e.apk", 300), file("/r/f.bin", 100)))
        assertEquals(listOf("image" to 1_000L, "audio" to 500L, "document" to 200L, "video" to 2_000L,
            "apps" to 300L, "other" to 100L, "system" to 1_900L), i.usage)
        assertEquals(6_000L, i.used)
    }

    @Test
    fun cleanupCountsEachFileOnce() {
        // big.mp4 is large AND old: it appears in two lists but counts once in the headline.
        val i = insights(listOf(file("/r/big.mp4", 2_000, ageDays = 400), file("/r/small.txt", 10)), trash = 50)
        assertEquals(2_000L, i.cleanup.toMap()["large"])
        assertEquals(2_000L, i.cleanup.toMap()["old"])
        assertEquals(2_050L, i.cleanupTotal)
    }

    @Test
    fun twelveMonthsOldestFirst() {
        val i = insights(listOf(file("/r/a.jpg", 100, ageDays = 1), file("/r/b.jpg", 50, ageDays = 40)))
        assertEquals(12, i.months.size)
        assertEquals("2026-11", i.months.last().first)
        assertEquals(100L, i.months.last().second)
        assertEquals(50L, i.months[10].second)
    }

    @Test
    fun suggestionsOnlyTopLevelDownload() {
        val i = insights(listOf(file("/r/Download/old.zip", 700, ageDays = 300),
            file("/r/Download/app.apk", 300), file("/r/Download/sub/deep.zip", 999, ageDays = 300)))
        assertEquals(listOf("old_downloads" to 700L, "download_apks" to 300L), i.suggestions.map { it.id to it.bytes })
    }
}
