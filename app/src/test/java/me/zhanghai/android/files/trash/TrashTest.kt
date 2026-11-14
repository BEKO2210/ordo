/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.trash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashTest {
    @Test
    fun rootOf_sharedStorage() {
        assertEquals("/storage/emulated/0", Trash.rootOf("/storage/emulated/0/DCIM/a.jpg"))
        assertEquals("/storage/emulated/10", Trash.rootOf("/storage/emulated/10/x"))
        assertEquals("/storage/1A2B-3C4D", Trash.rootOf("/storage/1A2B-3C4D/Music/b.mp3"))
    }

    @Test
    fun rootOf_notTrashable() {
        assertNull(Trash.rootOf("/storage/emulated/0"))
        assertNull(Trash.rootOf("/storage/emulated/0/.OrdoTrash"))
        assertNull(Trash.rootOf("/storage/emulated/0/.OrdoTrash/files/1-a"))
        assertNull(Trash.rootOf("/storage/self/primary/a"))
        assertNull(Trash.rootOf("/data/local/tmp/a"))
        assertNull(Trash.rootOf("/system/app"))
    }

    @Test
    fun rootOf_similarNameIsTrashable() {
        assertEquals("/storage/emulated/0", Trash.rootOf("/storage/emulated/0/.OrdoTrashX/a"))
    }

    @Test
    fun info_roundTrip() {
        val path = "/storage/emulated/0/Dir with = and : and\nnewline/ä.txt"
        val entry = Trash.parseInfo("1-ab", "/storage/emulated/0", Trash.formatInfo(path, 42L))!!
        assertEquals(path, entry.originalPath)
        assertEquals(42L, entry.deletedAt)
        assertEquals("ä.txt", entry.name)
    }

    @Test
    fun info_missingFieldsIsNull() {
        assertNull(Trash.parseInfo("1", "/r", java.util.Properties()))
    }

    @Test
    fun restoreTarget_findsFreeName() {
        val taken = setOf("/a/b.txt", "/a/b (1).txt")
        assertEquals("/a/c.txt", Trash.restoreTarget("/a/c.txt") { it in taken })
        assertEquals("/a/b (2).txt", Trash.restoreTarget("/a/b.txt") { it in taken })
        assertEquals("/a/dir (1)", Trash.restoreTarget("/a/dir") { it == "/a/dir" })
        assertEquals("/a/.hidden (1)", Trash.restoreTarget("/a/.hidden") { it == "/a/.hidden" })
    }

    @Test
    fun isExpired() {
        val day = 24L * 60 * 60 * 1000
        val entry = TrashEntry("1", "/r", "/r/a", 0L)
        assertFalse(Trash.isExpired(entry, 30, 30 * day))
        assertTrue(Trash.isExpired(entry, 30, 30 * day + 1))
        assertFalse(Trash.isExpired(entry, 0, 1000 * day))
    }
}
