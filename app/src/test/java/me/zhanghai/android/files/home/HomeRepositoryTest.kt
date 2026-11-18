/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRepositoryTest {
    private val roots = listOf("/storage/emulated/0", "/storage/1234-5678")

    @Test
    fun relativeDir_primaryAndSecondaryVolume() {
        assertEquals("Download", HomeRepository.relativeDir("/storage/emulated/0/Download/a.pdf", roots))
        assertEquals("Download", HomeRepository.relativeDir("/storage/1234-5678/Download/a.pdf", roots))
        assertEquals("", HomeRepository.relativeDir("/storage/1234-5678/a.pdf", roots))
    }

    @Test
    fun newestFilesChecksOnlyUntilEnoughFilesAreFound() {
        // (name, modified); "dir" entries are folders, which MediaStore lists too.
        val rows = (1..1000).map { "file$it" to it.toLong() } + listOf("dir-newest" to 5000L)
        val checked = mutableListOf<String>()
        val newest = newestFiles(rows, 3, { it.second }) { checked += it.first; !it.first.startsWith("dir") }
        assertEquals(listOf("file1000", "file999", "file998"), newest.map { it.first })
        assertEquals(listOf("dir-newest", "file1000", "file999", "file998"), checked)
    }
}
