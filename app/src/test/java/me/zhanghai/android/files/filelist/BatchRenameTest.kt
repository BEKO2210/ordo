/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Test

class BatchRenameTest {
    @Test
    fun keepsExtensionAndNumbers() {
        val names = BatchRename.newNames(
            listOf("IMG_1.jpg" to false, "b.PNG" to false, "Ordner" to true, ".hidden" to false),
            "Urlaub_{n}"
        )
        assertEquals(listOf("Urlaub_01.jpg", "Urlaub_02.PNG", "Urlaub_03", "Urlaub_04"), names)
    }

    @Test
    fun appendsNumberWithoutPlaceholderAndPadsToCount() {
        val files = (1..120).map { "f$it.txt" to false }
        val names = BatchRename.newNames(files, "Rom", 1)
        assertEquals("Rom 001.txt", names.first())
        assertEquals("Rom 120.txt", names.last())
    }

    @Test
    fun customStart() {
        assertEquals(listOf("x 09", "x 10"), BatchRename.newNames(listOf("a" to true, "b" to true), "x", 9))
    }

    @Test
    fun conflictFindsInvalidAndExistingTargets() {
        val taken = setOf("Urlaub 01.jpg")
        assertEquals(null, BatchRename.conflict(listOf("a.jpg" to "Urlaub 02.jpg"), taken::contains))
        assertEquals(null, BatchRename.conflict(listOf("Urlaub 01.jpg" to "Urlaub 01.jpg"), taken::contains))
        assertEquals(
            BatchRename.Conflict.EXISTS,
            BatchRename.conflict(listOf("a.jpg" to "Urlaub 01.jpg"), taken::contains)
        )
        assertEquals(
            BatchRename.Conflict.INVALID,
            BatchRename.conflict(listOf("a.mp3" to "AC/DC 01.mp3"), taken::contains)
        )
        assertEquals(BatchRename.Conflict.INVALID, BatchRename.conflict(listOf("a" to ""), taken::contains))
    }
}
