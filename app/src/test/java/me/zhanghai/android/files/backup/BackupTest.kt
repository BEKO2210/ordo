/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupTest {
    private val root = "/storage/emulated/0"

    @Test
    fun entryNameIsRelativeToStorage() {
        assertEquals("DCIM/Camera/a.jpg", backupEntryName("$root/DCIM/Camera/a.jpg", root))
    }

    @Test
    fun entryNameOutsideStorageKeepsFullPath() {
        assertEquals("storage/1A2B-3C4D/Fotos", backupEntryName("/storage/1A2B-3C4D/Fotos", root))
    }

    @Test
    fun entryNameDoesNotStripSimilarPrefix() {
        assertEquals("storage/emulated/01/a", backupEntryName("/storage/emulated/01/a", root))
    }

    @Test
    fun uniqueNameNumbersBeforeExtension() {
        val used = mutableSetOf<String>()
        assertEquals("Tresor/a.b/foto.jpg", uniqueEntryName("Tresor/a.b/foto.jpg", used))
        assertEquals("Tresor/a.b/foto (2).jpg", uniqueEntryName("Tresor/a.b/foto.jpg", used))
        assertEquals("Tresor/a.b/foto (3).jpg", uniqueEntryName("Tresor/a.b/foto.jpg", used))
    }

    @Test
    fun uniqueNameWithoutExtension() {
        val used = mutableSetOf("Tresor/a.b/notiz", "Tresor/.hidden")
        assertEquals("Tresor/a.b/notiz (2)", uniqueEntryName("Tresor/a.b/notiz", used))
        assertEquals("Tresor/.hidden (2)", uniqueEntryName("Tresor/.hidden", used))
    }

    @Test
    fun nestedFoldersAreDropped() {
        assertEquals(
            listOf("$root/DCIM", "$root/DCIM2"),
            withoutNestedFolders(listOf("$root/DCIM", "$root/DCIM/Camera", "$root/DCIM2", "$root/DCIM"))
        )
    }
}
