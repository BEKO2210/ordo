/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.backup

import org.junit.Assert.assertEquals
import org.junit.Test

/** A backup saved into a folder it backs up finds itself by these paths and leaves itself out. */
class BackupTargetPathsTest {
    private val root = "/storage/emulated/0"
    private val storage = "com.android.externalstorage.documents"

    @Test
    fun storageProviderDocumentOnTheInternalStorage() {
        assertEquals(
            setOf("/storage/emulated/0/ordo-e2e-selfbackup/Ordo-Sicherung.zip"),
            backupTargetPaths(storage, "primary:ordo-e2e-selfbackup/Ordo-Sicherung.zip", null, root)
        )
    }

    @Test
    fun storageProviderDocumentOnAnSdCard() {
        assertEquals(
            setOf("/storage/1A2B-3C4D/Backups/x.zip"),
            backupTargetPaths(storage, "1A2B-3C4D:Backups/x.zip", null, root)
        )
    }

    @Test
    fun fileDescriptorPathsBehindTheSharedStorageMapToStorage() {
        val expected = setOf("/storage/emulated/0/b/x.zip")
        for (fdPath in listOf(
            "/data/media/0/b/x.zip", "/mnt/user/0/emulated/0/b/x.zip", "/mnt/pass_through/0/emulated/0/b/x.zip",
            "/storage/emulated/0/b/x.zip"
        )) {
            assertEquals(fdPath, expected, backupTargetPaths("other.provider", null, fdPath, root))
        }
        assertEquals(setOf("/storage/1A2B-3C4D/x.zip"), backupTargetPaths(null, null, "/mnt/media_rw/1A2B-3C4D/x.zip", root))
    }

    @Test
    fun cloudProviderGivesNoPath() {
        assertEquals(emptySet<String>(), backupTargetPaths("com.google.android.apps.docs.storage", "acc=1;doc=9", "pipe:[123]", root))
    }

    @Test
    fun bothSourcesTogether() {
        assertEquals(
            setOf("/storage/emulated/0/a/x.zip"),
            backupTargetPaths(storage, "primary:a/x.zip", "/data/media/0/a/x.zip", root)
        )
    }
}
