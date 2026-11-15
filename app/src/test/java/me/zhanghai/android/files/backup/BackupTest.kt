/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.backup

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun sourceStreamStopsAtAnnouncedSize() {
        val stream = BackupSourceStream(ByteArrayInputStream(ByteArray(10) { it.toByte() }), 4, "a")
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), stream.readBytes())
    }

    @Test
    fun fileEndingEarlyFailsInsteadOfCuttingItsEntryShort() {
        val stream = BackupSourceStream(ByteArrayInputStream(ByteArray(3)), 10, "DCIM/video.mp4")
        val e = assertThrows(BackupSourceException::class.java) { stream.readBytes() }
        assertEquals("DCIM/video.mp4", e.fileName)
        assertTrue(e.cause is EOFException)
    }

    @Test
    fun readErrorMidwayFailsWithTheFileName() {
        val failing = object : InputStream() {
            var count = 0
            override fun read(): Int = if (++count > 3) throw IOException("bad sector") else 0
        }
        val e = assertThrows(BackupSourceException::class.java) {
            BackupSourceStream(failing, 10, "DCIM/video.mp4").readBytes()
        }
        assertEquals("DCIM/video.mp4", e.fileName)
    }

    @Test
    fun cancellingIsNoReadError() {
        val cancelled = object : InputStream() {
            override fun read(): Int = throw InterruptedIOException()
        }
        assertThrows(InterruptedIOException::class.java) {
            BackupSourceStream(cancelled, 10, "a").readBytes()
        }
    }
}
