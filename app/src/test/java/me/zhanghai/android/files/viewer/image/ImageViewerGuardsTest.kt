/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.image

import me.zhanghai.android.files.filelist.FileListFragment
import me.zhanghai.android.files.filelist.shouldFinishLostShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FLAG_SECURE, the vault-viewer timeout, private file URIs, and a show list that died with the process. */
class ImageViewerGuardsTest {
    private val pkg = "com.beko2210.ordo"
    private val roots = listOf("/data/data/$pkg", "/data/user/0/$pkg")

    @Test
    fun secureFlagOnlyOutsideDebugAndOnlyForTheVault() {
        val vault = listOf("content://$pkg.vault/e2e-secure-check")
        assertFalse(shouldSecureViewer(debuggable = true, vault, pkg))
        assertTrue(shouldSecureViewer(debuggable = false, vault, pkg))
        assertFalse(shouldSecureViewer(debuggable = false, listOf("file:///sdcard/a.jpg"), pkg))
        assertFalse(shouldSecureViewer(debuggable = false, listOf("content://other.vault/x"), pkg))
        assertFalse(shouldSecureViewer(debuggable = false, listOf("content://$pkg.vaultx/no"), pkg))
        assertFalse(shouldSecureViewer(debuggable = false, listOf(null, ""), pkg))
        assertTrue(isVaultContentUri("content://$pkg.vault/id", pkg))
    }

    @Test
    fun backgroundConfigChangeKeepsTheVaultLeaveTime() {
        val left = 10_000L
        // Recreated while stopped: this instance never had the window, then onStop runs again.
        assertEquals(
            left,
            vaultLeaveOnStop(
                isVault = true,
                finishing = false,
                changingConfigurations = false,
                inFront = false,
                stoppedAt = left,
                now = 40_000L
            )
        )
        assertTrue(vaultViewerTimedOut(left, left + VAULT_VIEWER_TIMEOUT_MILLIS + 1, VAULT_VIEWER_TIMEOUT_MILLIS))
        assertEquals(
            0L,
            vaultLeaveOnStop(
                isVault = true,
                finishing = false,
                changingConfigurations = true,
                inFront = true,
                stoppedAt = 0L,
                now = 5_000L
            )
        )
        assertEquals(
            5_000L,
            vaultLeaveOnStop(
                isVault = true,
                finishing = false,
                changingConfigurations = false,
                inFront = true,
                stoppedAt = 0L,
                now = 5_000L
            )
        )
        assertEquals(
            0L,
            vaultLeaveOnStop(
                isVault = false,
                finishing = false,
                changingConfigurations = false,
                inFront = true,
                stoppedAt = 0L,
                now = 5_000L
            )
        )
        assertEquals(
            left,
            vaultLeaveOnStop(
                isVault = true,
                finishing = true,
                changingConfigurations = false,
                inFront = false,
                stoppedAt = left,
                now = 40_000L
            )
        )
    }

    @Test
    fun timeoutMatchesTheVaultScreenUnlessDebugOverridesIt() {
        assertEquals(2 * 60 * 1000L, VAULT_VIEWER_TIMEOUT_MILLIS)
        assertFalse(vaultViewerTimedOut(stoppedAtElapsed = 0L, nowElapsed = 5_000L, timeoutMillis = 0L))
        assertFalse(vaultViewerTimedOut(100L, 200L, 100L))
        assertTrue(vaultViewerTimedOut(100L, 201L, 100L))
        assertEquals(VAULT_VIEWER_TIMEOUT_MILLIS, vaultViewerTimeoutMillis(debuggable = false, overrideMs = 0L))
        assertEquals(VAULT_VIEWER_TIMEOUT_MILLIS, vaultViewerTimeoutMillis(debuggable = true, overrideMs = null))
        assertEquals(VAULT_VIEWER_TIMEOUT_MILLIS, vaultViewerTimeoutMillis(debuggable = true, overrideMs = -1L))
        assertEquals(0L, vaultViewerTimeoutMillis(debuggable = true, overrideMs = 0L))
        assertEquals(1_000L, vaultViewerTimeoutMillis(debuggable = true, overrideMs = 1_000L))
    }

    @Test
    fun fileUriUnderAppDataIsRejectedIncludingDotSegments() {
        assertTrue(fileUriTargetsAppData("file:///data/data/$pkg/files/x.jpg", roots))
        assertTrue(fileUriTargetsAppData("file://localhost/data/data/$pkg/files/x.jpg", roots))
        assertTrue(fileUriTargetsAppData("file:///data/data/$pkg/files/../files/x.jpg?q=1", roots))
        assertTrue(
            fileUriTargetsAppData("file:///data/data/$pkg/files/%2e%2e/%2e%2e/$pkg/files/secret.jpg", roots)
        )
        assertFalse(fileUriTargetsAppData("file:///data/data/$pkg-other/files/x.jpg", roots))
        assertFalse(fileUriTargetsAppData("file:///data/data/${pkg}2/files/x.jpg", roots))
        assertFalse(
            fileUriTargetsAppData("file:///data/data/$pkg/files/%2e%2e/%2e%2e/%2e%2e/other/secret.jpg", roots)
        )
        assertFalse(fileUriTargetsAppData("file:///sdcard/a.png", roots))
        assertFalse(fileUriTargetsAppData("content://$pkg.vault/id", roots))
        assertFalse(fileUriTargetsAppData(null, roots))
    }

    @Test
    fun procRootAndFileProviderCannotReachAppData() {
        val packages = listOf(pkg)
        assertTrue(fileUriTargetsAppData("file:///proc/self/root/data/data/$pkg/files/secret.jpg", roots))
        assertTrue(fileUriTargetsAppData("file:///proc/self/root/data/user/0/$pkg/files/secret.jpg", roots))
        assertTrue(fileUriTargetsAppData("file:///proc/999999/root/data/data/$pkg/files/secret.jpg", roots))
        assertTrue(fileUriTargetsAppData("file://localhost/proc/thread-self/root/data/data/$pkg/a.jpg", roots))
        assertFalse(fileUriTargetsAppData("file:///proc/self/root/sdcard/a.jpg", roots))
        val provider =
            "content://$pkg.file_provider/file%3A%2F%2F%2Fdata%2Fuser%2F0%2F$pkg%2Ffiles%2Fx.jpg"
        assertTrue(externalImageUriRejected(provider, roots, packages))
        assertTrue(
            externalImageUriRejected(
                "content://user@$pkg.file_provider/file%3A%2F%2F%2Fproc%2Fself%2Froot%2Fdata%2Fdata%2F$pkg%2Fx.jpg",
                roots,
                packages
            )
        )
        assertFalse(
            externalImageUriRejected(
                "content://$pkg.file_provider/file%3A%2F%2F%2Fsdcard%2Fa.jpg",
                roots,
                packages
            )
        )
        assertFalse(
            externalImageUriRejected(
                "content://$pkg.file_provider/smb%3A%2F%2Fhost%2Fshare%2Fa.jpg",
                roots,
                packages
            )
        )
        assertFalse(externalImageUriRejected("content://media/external/images/1", roots, packages))
        assertFalse(externalImageUriRejected("file:///sdcard/a.png", roots, packages))
        assertTrue(launchTokenValid(1_000L, 1_000L))
        assertFalse(launchTokenValid(1_000L, 1_001L))
        assertFalse(launchTokenValid(null, 0L))
        assertEquals(30_000L, IMAGE_VIEWER_LAUNCH_TOKEN_TTL_MILLIS)
    }

    @Test
    fun symlinkIntoAppDataIsRejected() {
        val root = java.nio.file.Files.createTempDirectory("ordo-root")
        val outside = java.nio.file.Files.createTempDirectory("ordo-out")
        val link = outside.resolve("link")
        try {
            java.nio.file.Files.createDirectories(root.resolve("files"))
            java.nio.file.Files.createSymbolicLink(link, root)
            val secret = link.resolve("files").resolve("a.jpg").toUri().toString()
            assertTrue(fileUriTargetsAppData(secret, listOf(root.toString())))
            val plain = outside.resolve("plain.jpg").toUri().toString()
            assertFalse(fileUriTargetsAppData(plain, listOf(root.toString())))
        } finally {
            java.nio.file.Files.deleteIfExists(link)
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun lostShowFinishesOnlyWhenTheRestoredRequestIsGone() {
        assertTrue(shouldFinishLostShow(restored = true, action = null, showToken = "token", requestFound = false))
        assertFalse(shouldFinishLostShow(restored = false, action = null, showToken = "forged", requestFound = false))
        assertFalse(shouldFinishLostShow(restored = true, action = null, showToken = "token", requestFound = true))
        assertFalse(
            shouldFinishLostShow(
                restored = true, action = "android.intent.action.VIEW", showToken = "token", requestFound = false
            )
        )
        assertFalse(shouldFinishLostShow(restored = true, action = null, showToken = null, requestFound = false))
        assertFalse(shouldFinishLostShow(restored = true, action = null, showToken = "", requestFound = false))
    }

    @Test
    fun imagePathListStaysShortAndStateIsKeptOnlyAfterAChange() {
        assertEquals(200, FileListFragment.IMAGE_VIEWER_ACTIVITY_PATH_LIST_SIZE_MAX)
        assertFalse(shouldKeepImageViewerState(listOf("a", "b"), listOf("a", "b")))
        assertTrue(shouldKeepImageViewerState(listOf("a", "b"), listOf("b")))
    }

    @Test
    fun restoreAfterProcessDeathWorksWithoutSavedState() {
        val opened = listOf("a", "b")
        // Unchanged list: nothing is saved (monkey crash, W90 26.11.2026), the restore takes the intent's list.
        val saved = opened.takeIf { shouldKeepImageViewerState(opened, opened) }
        assertEquals(opened, restoredImageViewerPaths(saved, opened))
        // After a delete the saved list wins.
        val afterDelete = listOf("b")
        val savedAfterDelete = afterDelete.takeIf { shouldKeepImageViewerState(opened, afterDelete) }
        assertEquals(afterDelete, restoredImageViewerPaths(savedAfterDelete, opened))
    }
}
