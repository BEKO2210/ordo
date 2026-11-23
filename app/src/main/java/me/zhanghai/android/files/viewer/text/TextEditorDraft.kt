/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.text

import java.io.File
import java.security.MessageDigest

/**
 * Unsaved editor text, kept in the cache so a killed process can bring it back.
 * The view hierarchy must not hold it: a whole file in the instance state blows the binder limit.
 * Rotation does not need this; the view model still has the editor state then.
 */
internal object TextEditorDraft {
    private const val PREFIX = "text-editor-draft-"

    fun key(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun write(cacheDir: File, key: String, text: String) {
        val target = file(cacheDir, key)
        val tmp = File(cacheDir, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun read(cacheDir: File, key: String): String? {
        val target = file(cacheDir, key)
        if (!target.isFile) return null
        return target.readText(Charsets.UTF_8)
    }

    fun delete(cacheDir: File, key: String) {
        file(cacheDir, key).delete()
        File(cacheDir, file(cacheDir, key).name + ".tmp").delete()
    }

    private fun file(cacheDir: File, key: String): File = File(cacheDir, PREFIX + key)
}

/** A draft is applied only when the view-model copy did not survive (process death, not rotation). */
internal fun editorDraftToApply(viewStateKept: Boolean, draft: String?): String? =
    if (viewStateKept) null else draft

/** False when the user typed after the snapshot that was written, so the editor stays dirty. */
internal fun savedTextIsCurrent(current: String, saved: String): Boolean = current == saved
