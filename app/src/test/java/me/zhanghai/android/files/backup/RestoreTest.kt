/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.backup

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreTest {
    @Test
    fun mapsNormalEntryUnderStorageRoot() {
        val root = Files.createTempDirectory("ordo-restore-test").toFile()
        val entry = restoreEntryPath("DCIM/Camera/photo.jpg")

        assertFalse(entry.isVault)
        assertEquals(
            root.resolve("DCIM/Camera/photo.jpg").canonicalFile,
            resolveRestoreFile(root, entry.relativePath)
        )
    }

    @Test
    fun recognizesBothVaultFolderNamesFromExistingBackups() {
        assertTrue(restoreEntryPath("Vault/photo.jpg").isVault)
        assertTrue(restoreEntryPath("Tresor/photo.jpg").isVault)
    }

    @Test
    fun rejectsAbsoluteAndTraversalPaths() {
        assertRejected("/DCIM/photo.jpg")
        assertRejected("C:/DCIM/photo.jpg")
        assertRejected("DCIM/../Secrets/file.txt")
        assertRejected("DCIM/./photo.jpg")
        assertRejected("DCIM//photo.jpg")
    }

    @Test
    fun rejectsBackslashesAndControlCharacters() {
        assertRejected("DCIM\\photo.jpg")
        assertRejected("DCIM/bad\u0000name.jpg")
        assertRejected("DCIM/bad\u0085name.jpg")
    }

    @Test
    fun rejectsProtectedAndroidFolders() {
        assertRejected("Android/data/app/file")
        assertRejected("android/OBB/app/file")
    }

    @Test
    fun canonicalCheckRejectsExistingSymlinkOutsideStorageRoot() {
        val root = Files.createTempDirectory("ordo-restore-root").toFile()
        val outside = Files.createTempDirectory("ordo-restore-outside")
        Files.createSymbolicLink(root.resolve("Pictures").toPath(), outside)

        assertThrows(IllegalArgumentException::class.java) {
            resolveRestoreFile(root, "Pictures/photo.jpg")
        }
    }

    @Test
    fun samePathAndSizeIsSkipped() {
        val states = mapOf(
            "DCIM/photo.jpg" to RestoreDestinationState.ExistingFile(42)
        )

        assertEquals(
            RestoreDestination.Skip,
            chooseRestoreDestination("DCIM/photo.jpg", 42) {
                states[it] ?: RestoreDestinationState.Missing
            }
        )
    }

    @Test
    fun differentSizeGetsClearSuffixBeforeExtension() {
        val states = mapOf(
            "DCIM/photo.jpg" to RestoreDestinationState.ExistingFile(41)
        )

        assertEquals(
            RestoreDestination.Write("DCIM/photo (restored).jpg"),
            chooseRestoreDestination("DCIM/photo.jpg", 42) {
                states[it] ?: RestoreDestinationState.Missing
            }
        )
    }

    @Test
    fun existingDirectoryIsNotReplaced() {
        assertEquals(
            RestoreDestination.Write("Pictures/Camera (restored)"),
            chooseRestoreDestination("Pictures/Camera", 42) {
                if (it == "Pictures/Camera") {
                    RestoreDestinationState.ExistingOther
                } else {
                    RestoreDestinationState.Missing
                }
            }
        )
    }

    @Test
    fun restoringTwiceAddsNoSecondCopy() {
        val states = mapOf(
            "Documents/report.txt" to RestoreDestinationState.ExistingFile(18),
            "Documents/report (restored).txt" to RestoreDestinationState.ExistingFile(5)
        )

        assertEquals(
            RestoreDestination.Skip,
            chooseRestoreDestination("Documents/report.txt", 5) { states[it] ?: RestoreDestinationState.Missing }
        )
    }

    @Test
    fun occupiedSuffixIsNumberedAndNeverReused() {
        val states = mapOf(
            "Documents/report.txt" to RestoreDestinationState.ExistingFile(1),
            "Documents/report (restored).txt" to RestoreDestinationState.ExistingFile(2),
            "Documents/report (restored 2).txt" to RestoreDestinationState.ExistingOther
        )

        assertEquals(
            RestoreDestination.Write("Documents/report (restored 3).txt"),
            chooseRestoreDestination("Documents/report.txt", 3) {
                states[it] ?: RestoreDestinationState.Missing
            }
        )
    }

    @Test
    fun hiddenFileSuffixStaysReadable() {
        assertEquals(
            RestoreDestination.Write("Download/.nomedia (restored)"),
            chooseRestoreDestination("Download/.nomedia", 1) {
                if (it == "Download/.nomedia") {
                    RestoreDestinationState.ExistingFile(2)
                } else {
                    RestoreDestinationState.Missing
                }
            }
        )
    }

    private fun assertRejected(name: String) {
        assertThrows(IllegalArgumentException::class.java) { restoreEntryPath(name) }
    }
}
