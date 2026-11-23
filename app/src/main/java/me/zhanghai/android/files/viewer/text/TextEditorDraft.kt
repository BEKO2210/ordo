/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.text

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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
        return try {
            if (!target.isFile) null else target.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            null
        }
    }

    /** Cache reads stay off the main thread. [io] is the dispatcher the read runs on. */
    suspend fun readSuspending(
        cacheDir: File,
        key: String,
        io: CoroutineDispatcher = Dispatchers.IO,
    ): String? = withContext(io) { read(cacheDir, key) }

    fun delete(cacheDir: File, key: String) {
        file(cacheDir, key).delete()
        File(cacheDir, file(cacheDir, key).name + ".tmp").delete()
    }

    private fun file(cacheDir: File, key: String): File = File(cacheDir, PREFIX + key)
}

/**
 * A draft is applied only when the view-model copy did not survive (process death, not rotation)
 * and the user has not typed since the file was shown. The read is asynchronous, so keystrokes win.
 */
internal fun editorDraftToApply(viewStateKept: Boolean, typedSinceLoad: Boolean, draft: String?): String? =
    if (viewStateKept || typedSinceLoad || draft == null) null else draft

/** False when the user typed after the snapshot that was written, so the editor stays dirty. */
internal fun savedTextIsCurrent(current: String, saved: String): Boolean = current == saved
