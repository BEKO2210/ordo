/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.provider.document.resolver

import java.io.IOException
import java.io.InterruptedIOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruptedDocumentMoveTest {
    @Test
    fun rollbackRestoresTheOriginalNameAfterMovingBack() {
        var name = "new-name.jpg"
        try {
            InterruptedDocumentMove.abortAfterTransfer {
                InterruptedDocumentMove.restoreName("old-name.jpg", name) { name = it }
            }
            throw AssertionError("expected InterruptedIOException")
        } catch (e: InterruptedIOException) {
            assertEquals("old-name.jpg", name)
        }
    }

    @Test
    fun failedRollbackDoesNotLookLikeACleanInterrupt() {
        val thrown = assertThrows(IOException::class.java) {
            InterruptedDocumentMove.abortAfterTransfer {
                throw IOException("provider refused")
            }
        }
        assertFalse(thrown is InterruptedIOException)
        assertEquals("Move was interrupted after the document left the source folder", thrown.message)
        assertTrue(thrown.cause is IOException)
    }

    @Test
    fun successfulRollbackThrowsInterruptedIOException() {
        assertThrows(InterruptedIOException::class.java) {
            InterruptedDocumentMove.abortAfterTransfer {}
        }
    }

    @Test
    fun restoreNameSkipsWhenTheNameAlreadyMatches() {
        var renamed: String? = null
        InterruptedDocumentMove.restoreName("foto.jpg", "foto.jpg") { renamed = it }
        assertEquals(null, renamed)
        assertFalse(InterruptedDocumentMove.shouldRestoreName("foto.jpg", "foto.jpg"))
        assertTrue(InterruptedDocumentMove.shouldRestoreName("foto.jpg", "copy.jpg"))
        assertTrue(InterruptedDocumentMove.shouldRestoreName("foto.jpg", null))
    }
}
