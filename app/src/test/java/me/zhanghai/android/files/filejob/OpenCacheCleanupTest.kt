/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.filejob

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCacheCleanupTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun removesOnlyEntriesOlderThanLimit() {
        val now = 10_000_000L
        val old = folder.newFile("old.txt").apply { setLastModified(now - 7_200_000) }
        val oldDir = folder.newFolder("dir").also {
            File(it, "inner.txt").writeText("x")
            it.setLastModified(now - 7_200_000)
        }
        val fresh = folder.newFile("fresh.txt").apply { setLastModified(now - 60_000) }
        deleteStaleCacheEntries(folder.root, now, 3_600_000)
        assertFalse(old.exists())
        assertFalse(oldDir.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun missingDirectoryIsFine() {
        deleteStaleCacheEntries(File(folder.root, "none"), 0, 1)
    }
}
