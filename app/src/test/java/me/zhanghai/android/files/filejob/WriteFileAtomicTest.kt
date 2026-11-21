/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.filejob

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WriteFileAtomicTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun write(file: File, temp: File, failWhileWriting: Boolean) =
        writeReplacing(
            {
                temp.writeText("new")
                if (failWhileWriting) {
                    throw IOException("disk full")
                }
            },
            {
                Files.move(
                    temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            },
            { Files.deleteIfExists(temp.toPath()) }
        )

    @Test
    fun failedWriteLeavesOriginalAndNoTempFile() {
        val file = folder.newFile("a.txt").apply { writeText("old") }
        val temp = File(folder.root, ".a.txt.ordo-tmp")
        try {
            write(file, temp, true)
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertEquals("disk full", e.message)
        }
        assertEquals("old", file.readText())
        assertFalse(temp.exists())
    }

    @Test
    fun successfulWriteReplacesOriginal() {
        val file = folder.newFile("a.txt").apply { writeText("old") }
        val temp = File(folder.root, ".a.txt.ordo-tmp")
        write(file, temp, false)
        assertEquals("new", file.readText())
        assertFalse(temp.exists())
    }

    @Test
    fun failedMoveCleansUp() {
        var cleaned = false
        try {
            writeReplacing({}, { throw IOException("move") }, { cleaned = true })
        } catch (e: IOException) {
            assertTrue(cleaned)
            return
        }
        throw AssertionError("expected IOException")
    }
}
