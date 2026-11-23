/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.provider.document.resolver

import java.io.IOException
import java.io.InterruptedIOException

internal object InterruptedDocumentMove {
    fun shouldRestoreName(originalName: String?, currentName: String?): Boolean =
        originalName != null && originalName != currentName

    @Throws(IOException::class)
    fun restoreName(
        originalName: String?,
        currentName: String?,
        rename: (String) -> Unit
    ) {
        if (shouldRestoreName(originalName, currentName)) {
            rename(originalName!!)
        }
    }

    @Throws(IOException::class)
    fun abortAfterTransfer(rollback: () -> Unit) {
        try {
            rollback()
        } catch (e: Exception) {
            throw IOException(
                "Move was interrupted after the document left the source folder", e
            )
        }
        throw InterruptedIOException()
    }
}
