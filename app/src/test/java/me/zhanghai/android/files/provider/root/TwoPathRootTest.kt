/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.provider.root

import java8.nio.file.AccessDeniedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** copy/move/link: root acts on both paths, so a planted symlink on the side that needs no root is refused. */
class TwoPathRootTest {
    private class FakePath(
        val root: Boolean,
        val sharedSymlink: Boolean = false,
        val onlyWhenFollowed: Boolean = false
    ) : RootablePath {
        override fun isRootRequired(isAttributeAccess: Boolean, noFollowLinks: Boolean) = root
        override fun followsSharedSymlink(noFollowLinks: Boolean) =
            sharedSymlink && !(onlyWhenFollowed && noFollowLinks)
        override fun toString() = "/sdcard/planted"
    }

    @Test
    fun bothReachableNeedNoRoot() {
        assertFalse(isRootRequired(FakePath(false, true), FakePath(false, true), false, false, false))
    }

    @Test
    fun plainSourceIntoRootTargetTakesRoot() {
        assertTrue(isRootRequired(FakePath(false), FakePath(true), false, false, true))
        assertTrue(isRootRequired(FakePath(true), FakePath(false), false, true, true))
        assertTrue(isRootRequired(FakePath(true), FakePath(true), false, false, false))
    }

    @Test(expected = AccessDeniedException::class)
    fun plantedSourceIntoRootTargetIsRefused() {
        isRootRequired(FakePath(false, true), FakePath(true), false, false, true)
    }

    @Test(expected = AccessDeniedException::class)
    fun plantedTargetForARootSourceIsRefused() {
        isRootRequired(FakePath(true), FakePath(false, true), false, false, false)
    }

    @Test
    fun theRefusalSaysWhy() {
        try {
            isRootRequired(FakePath(false, true), FakePath(true), false, false, true)
        } catch (e: AccessDeniedException) {
            assertEquals("/sdcard/planted", e.file)
            assertTrue(e.reason!!.contains("symbolic link"))
            return
        }
        throw AssertionError("not refused")
    }

    @Test
    fun aLinkMovedAsItselfIsNotFollowed() {
        // move renames the link itself: its target is never touched, so root may move it.
        assertTrue(isRootRequired(FakePath(false, true, onlyWhenFollowed = true), FakePath(true), false, true, true))
    }
}
