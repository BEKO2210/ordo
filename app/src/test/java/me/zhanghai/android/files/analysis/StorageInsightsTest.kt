/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.analysis

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class StorageInsightsTest {
    private val day = 86_400_000L
    private val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 15, 12, 0) }.timeInMillis
    private fun file(path: String, size: Long, ageDays: Int = 1) = ScannedFile(path, size, now - ageDays * day)

    private fun insights(files: List<ScannedFile>, total: Long = 10_000, free: Long = 4_000, trash: Long = 0,
                         emptyFolders: List<ScannedFile> = emptyList()): StorageInsights {
        val result = AnalysisResult(
            "/r", StorageAnalysis.largeFiles(files, 500), StorageAnalysis.duplicates(files) { false },
            StorageAnalysis.oldFiles(files, now), StorageAnalysis.apkFiles(files), emptyList(), files,
            emptyFolders = emptyFolders
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
        assertEquals(4_100L, i.own)
    }

    @Test
    fun cleanupHeadlineCountsOnlySafeTypesOnce() {
        // big.mp4 is large AND old: worth a look, but not counted as safe to free.
        // The old APK is in the APK list and the old list; it counts once, as an installer.
        val i = insights(listOf(file("/r/big.mp4", 2_000, ageDays = 400), file("/r/small.txt", 10),
            file("/r/a.apk", 300, ageDays = 400)), trash = 50)
        assertEquals(listOf("duplicates", "apks", "thumbnails", "trash", "large", "old", "empty_folders"),
            i.cleanup.map { it.first })
        assertEquals(2_000L, i.cleanup.toMap()["large"])
        assertEquals(2_300L, i.cleanup.toMap()["old"])
        assertEquals(300L + 50L, i.cleanupTotal)
    }

    @Test
    fun thumbnailCachesAreSafeAndEmptyFoldersCounted() {
        val i = insights(listOf(file("/r/DCIM/.thumbnails/a.jpg", 40), file("/r/DCIM/.thumbnails/.thumbdata3", 400),
            file("/r/Android/data/app/.thumbnails/b.jpg", 70), file("/r/DCIM/Camera/c.jpg", 900)),
            emptyFolders = listOf(ScannedFile("/r/Old/Empty", 0, now), ScannedFile("/r/Leaf", 0, now)))
        assertEquals(440L, i.cleanup.toMap()["thumbnails"])
        assertEquals(440L, i.cleanupTotal)
        // Empty folders free nothing: counted, never added to what is safe to free.
        assertEquals(0L, i.cleanup.toMap()["empty_folders"])
        assertEquals(2, i.emptyFolders)
    }

    @Test
    fun shareNeverRoundsSmallPartsAway() {
        fun de(part: Long, whole: Long) = StorageInsights.share(part, whole, Locale.GERMANY).replace('\u00A0', ' ').replace('\u202F', ' ')
        fun en(part: Long, whole: Long) = StorageInsights.share(part, whole, Locale.US)
        // The owner's phone: 1.76 GB pictures of 241 GB showed "1 %".
        assertEquals("0,7 %", de(1_760, 241_000))
        assertEquals("0.7%", en(1_760, 241_000))
        assertEquals("<0,1 %", de(1, 10_000))
        assertEquals("9,9 %", de(994, 10_000))
        assertEquals("10 %", de(996, 10_000))
        assertEquals("57 %", de(57, 100))
        assertEquals(">99 %", de(9_996, 10_000))
        assertEquals("100 %", de(5, 5))
        assertEquals("0 %", de(0, 100))
        assertEquals("0 %", de(5, 0))
    }

    @Test
    fun folderKindNamesAppAndHiddenFolders() {
        assertEquals("apps", StorageInsights.folderKind("Android"))
        assertEquals("hidden", StorageInsights.folderKind(".trash-storage"))
        assertEquals(null, StorageInsights.folderKind("DCIM"))
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
