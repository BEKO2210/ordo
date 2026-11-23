/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.provider.linux

import java.io.File
import me.zhanghai.android.files.provider.common.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxCopyMoveTest {
    @Test
    fun partFileSitsNextToTheTarget() {
        assertEquals(
            "/storage/emulated/0/.photo.jpg.ordo-part".toByteString(),
            LinuxCopyMove.partFile("/storage/emulated/0/photo.jpg".toByteString())
        )
        assertEquals(
            ".name.ordo-part".toByteString(),
            LinuxCopyMove.partFile("name".toByteString())
        )
    }

    @Test
    fun partFileShortensNamesThatWouldExceedNameMax() {
        val name = "a".repeat(245).toByteString()
        val part = LinuxCopyMove.partFile("/storage/emulated/0/".toByteString() + name)
        val fileName = part.toString().substringAfterLast('/')
        assertTrue(fileName.length <= 255)
        assertTrue(fileName.startsWith(".ordo-part-"))
        assertTrue(LinuxCopyMove.isPartFileName(fileName.toByteString()))
    }

    @Test
    fun isPartFileNameMatchesHiddenPartFiles() {
        assertTrue(LinuxCopyMove.isPartFileName(".photo.jpg.ordo-part".toByteString()))
        assertTrue(LinuxCopyMove.isPartFileName(".ordo-part-deadbeef".toByteString()))
        assertFalse(LinuxCopyMove.isPartFileName("photo.jpg".toByteString()))
        assertFalse(LinuxCopyMove.isPartFileName("notes.ordo-part".toByteString()))
    }

    @Test
    fun newFileCopyCreatesTheTargetExclusively() {
        val source = File("src/main/java/me/zhanghai/android/files/provider/linux/LinuxCopyMove.kt")
            .readText()
        val copy = source
            .substringAfter("if (OsConstants.S_ISREG(sourceStat.st_mode))")
            .substringBefore("} else if (OsConstants.S_ISDIR(sourceStat.st_mode))")
        assertTrue(copy.contains("openExclusivePart"))
        assertTrue(source.contains("O_EXCL"))
        assertFalse(source.contains("removeLeftoverPartFiles"))
        assertFalse(source.contains("hasRoomForPart"))
    }

    @Test
    fun sameInodeStillRenamesWhenOnlyTheSpellingDiffers() {
        val lower = "/sdcard/foto.jpg".toByteString()
        val mixed = "/sdcard/Foto.jpg".toByteString()
        assertTrue(LinuxCopyMove.sameInodeStillNeedsRename(lower, mixed))
        assertFalse(LinuxCopyMove.sameInodeStillNeedsRename(lower, lower))
    }
}
