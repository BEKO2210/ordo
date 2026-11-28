/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.filelist

import java.io.File
import java8.nio.file.Paths
import java8.nio.file.attribute.BasicFileAttributes
import java.text.Collator
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.MimeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileListFocusTest {
    private val attributes = object : BasicFileAttributes {
        override fun lastModifiedTime() = java8.nio.file.attribute.FileTime.fromMillis(0)
        override fun lastAccessTime() = lastModifiedTime()
        override fun creationTime() = lastModifiedTime()
        override fun isRegularFile() = true
        override fun isDirectory() = false
        override fun isSymbolicLink() = false
        override fun isOther() = false
        override fun size() = 1L
        override fun fileKey(): Any? = null
    }

    private fun file(path: String): FileItem {
        val parsed = Paths.get(path)
        return FileItem(
            parsed, Collator.getInstance().getCollationKey(parsed.fileName.toString()), attributes,
            null, null, false, MimeType.GENERIC
        )
    }

    @Test fun findsFocusedFileByFullPath() {
        val files = listOf(file("/Music/same.mp3"), file("/Download/same.mp3"))
        assertEquals(1, focusedFileIndex(files, Paths.get("/Download/same.mp3")))
    }

    @Test fun findsFocusedFileAtItsDisplayedPositionAfterSorting() {
        val files = listOf(file("/Music/z.mp3"), file("/Music/a.mp3"))
        val displayedFiles = files.sortedWith(
            FileSortOptions(
                FileSortOptions.By.NAME, FileSortOptions.Order.ASCENDING,
                isDirectoriesFirst = true
            ).createComparator()
        )

        assertEquals(0, focusedFileIndex(displayedFiles, Paths.get("/Music/a.mp3")))
    }

    @Test fun fragmentFocusUsesDisplayedAdapterOrder() {
        val repo = File(System.getProperty("user.dir")!!).let {
            if (File(it, "README.md").exists()) it else it.parentFile!!
        }
        val source = File(
            repo, "app/src/main/java/me/zhanghai/android/files/filelist/FileListFragment.kt"
        ).readText()
        val focus = source.substringAfter("focusPath?.takeUnless { focusHandled }")
            .substringBefore("private fun getSubtitle")

        assertTrue(focus.contains("focusedFileIndex(adapter.list, path)"))
        assertTrue(focus.contains("val file = adapter.getItem(index)"))
    }

    @Test fun missingFocusedFileIsNotSelected() {
        assertEquals(-1, focusedFileIndex(listOf(file("/Music/song.mp3")), Paths.get("/Music/gone.mp3")))
    }
}
