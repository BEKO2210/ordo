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
}
