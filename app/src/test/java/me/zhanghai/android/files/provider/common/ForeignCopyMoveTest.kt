/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.provider.common

import java.io.File
import java8.nio.file.Paths
import java8.nio.file.spi.FileSystemProvider
import me.zhanghai.android.files.provider.linux.LinuxFileSystemProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class ForeignCopyMoveTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun fileSystem() {
            runCatching { FileSystemProvider.installDefaultProvider(LinuxFileSystemProvider) }
        }
    }

    @Test
    fun partFileSitsNextToTheTarget() {
        val target = Paths.get("/storage/emulated/0/Download/clip.mp4")
        assertEquals(
            Paths.get("/storage/emulated/0/Download/.clip.mp4.ordo-part"),
            ForeignCopyMove.partFile(target)
        )
    }

    @Test
    fun uniquePartFileDoesNotReuseTheDeterministicName() {
        val target = Paths.get("/storage/emulated/0/Download/clip.mp4")
        val unique = ForeignCopyMove.uniquePartFile(target)
        assertEquals(target.parent, unique.parent)
        assertNotEquals(ForeignCopyMove.partFile(target), unique)
        val name = unique.fileName.toString()
        assertTrue(name.startsWith(".clip.mp4.ordo-part-") || name.startsWith(".ordo-part-"))
    }

    @Test
    fun partFileShortensNamesThatWouldExceedNameMax() {
        val name = "a".repeat(245)
        val part = ForeignCopyMove.partFile(Paths.get("/storage/emulated/0/Download/$name"))
        val fileName = part.fileName.toString()
        assertTrue(fileName.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(fileName.startsWith(".ordo-part-"))
        assertTrue(ForeignCopyMove.isPartFileName(fileName))
    }

    @Test
    fun isPartFileNameMatchesHiddenPartFiles() {
        assertTrue(ForeignCopyMove.isPartFileName(".clip.mp4.ordo-part"))
        assertTrue(ForeignCopyMove.isPartFileName(".ordo-part-deadbeef"))
        assertFalse(ForeignCopyMove.isPartFileName("clip.mp4"))
        assertFalse(ForeignCopyMove.isPartFileName("notes.ordo-part"))
    }

    @Test
    fun backupFileSitsNextToTheTarget() {
        val target = Paths.get("/storage/emulated/0/Download/clip.mp4")
        assertEquals(
            Paths.get("/storage/emulated/0/Download/.clip.mp4.ordo-bak"),
            ForeignCopyMove.backupFile(target)
        )
        val unique = ForeignCopyMove.uniqueBackupFile(target)
        assertNotEquals(ForeignCopyMove.backupFile(target), unique)
        assertTrue(unique.fileName.toString().startsWith(".clip.mp4.ordo-bak-"))
    }

    @Test
    fun replaceDoesNotDeleteTheTargetBeforeThePartIsRenamed() {
        val replace = File("src/main/java/me/zhanghai/android/files/provider/common/ForeignCopyMove.kt")
            .readText()
            .substringAfter("private fun replaceWithPart")
            .substringBefore("private fun moveWithoutReplace")
        assertTrue(replace.contains("PartReplace.replace"))
        assertTrue(replace.contains("backupFile(target)"))
        assertTrue(replace.contains("uniqueBackupFile(target)"))
        assertFalse(replace.contains("target.deleteIfExists()"))
        assertFalse(replace.contains("REPLACE_EXISTING"))
    }

    @Test
    fun replaceWithoutAnExistingTargetDoesNotDeleteAUserBackup() {
        val files = mutableMapOf(
            "temp" to "new",
            ".foto.jpg.ordo-bak" to "user-bak"
        )
        PartReplace.replace(
            temp = "temp",
            target = "foto.jpg",
            replaceExisting = true,
            targetExists = false,
            backups = sequenceOf(".foto.jpg.ordo-bak"),
            move = { source, destination ->
                if (files.containsKey(destination)) {
                    throw java8.nio.file.FileAlreadyExistsException(source, destination, null)
                }
                files[destination] = files.remove(source)!!
            },
            delete = { files.remove(it) }
        )
        assertEquals("new", files["foto.jpg"])
        assertEquals("user-bak", files[".foto.jpg.ordo-bak"])
        assertFalse(files.containsKey("temp"))
    }

    @Test
    fun replaceDeletesOnlyTheBackupItCreated() {
        val files = mutableMapOf(
            "temp" to "new",
            "foto.jpg" to "old"
        )
        PartReplace.replace(
            temp = "temp",
            target = "foto.jpg",
            replaceExisting = true,
            targetExists = true,
            backups = sequenceOf(".foto.jpg.ordo-bak"),
            move = { source, destination ->
                if (files.containsKey(destination)) {
                    throw java8.nio.file.FileAlreadyExistsException(source, destination, null)
                }
                files[destination] = files.remove(source)!!
            },
            delete = { files.remove(it) }
        )
        assertEquals("new", files["foto.jpg"])
        assertFalse(files.containsKey(".foto.jpg.ordo-bak"))
        assertFalse(files.containsKey("temp"))
    }

    @Test
    fun copyWritesThroughAnExclusivePartFile() {
        val source = File("src/main/java/me/zhanghai/android/files/provider/common/ForeignCopyMove.kt")
            .readText()
        assertTrue(source.contains("copyRegularFileToPart"))
        assertTrue(source.contains("CREATE_NEW"))
        assertFalse(source.contains("removeLeftoverPartFiles"))
        assertFalse(source.contains("hasRoomForPart"))
        assertFalse(source.contains("TRUNCATE_EXISTING"))
    }
}
