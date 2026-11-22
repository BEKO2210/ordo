/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.provider.linux

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
    fun sameInodeStillRenamesWhenOnlyTheSpellingDiffers() {
        val lower = "/sdcard/foto.jpg".toByteString()
        val mixed = "/sdcard/Foto.jpg".toByteString()
        assertTrue(LinuxCopyMove.sameInodeStillNeedsRename(lower, mixed))
        assertFalse(LinuxCopyMove.sameInodeStillNeedsRename(lower, lower))
    }
}
