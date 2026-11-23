/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.backup

import java.io.File

/**
 * Remembers the backup file that was already cut, so a process kill can delete that half-written
 * archive on the next start. A backup that reached fsync clears this before it reports success.
 */
internal object CutBackup {
    private const val NAME = "backup-cut-uri"

    fun mark(directory: File, uri: String) {
        directory.mkdirs()
        val target = File(directory, NAME)
        val temporary = File(directory, "$NAME.tmp")
        temporary.writeText(uri)
        if (!temporary.renameTo(target)) {
            target.writeText(uri)
            temporary.delete()
        }
    }

    fun peek(directory: File): String? {
        val text = File(directory, NAME).takeIf { it.isFile }?.readText()?.trim().orEmpty()
        return text.ifEmpty { null }
    }

    fun clear(directory: File) {
        File(directory, NAME).delete()
        File(directory, "$NAME.tmp").delete()
    }
}
