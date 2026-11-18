/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.documents

import android.Manifest
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.supportsExternalStorageManager
import me.zhanghai.android.files.util.valueCompat

/**
 * Ordo as a place in Android's own file picker (Storage Access Framework): one read-only root, "Ordo", whose top level
 * is the user's favourite folders. Another app gets only what the user picks there, read-only. Nothing is shown
 * without all-files access, nothing outside the favourites, never app-private folders or the vault.
 * The system alone may call it (android.permission.MANAGE_DOCUMENTS in the manifest).
 */
class OrdoDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    private fun favourites(): List<Pair<File, String>> {
        val context = context ?: return emptyList()
        val hasAccess = if (Environment::class.supportsExternalStorageManager()) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
        if (!hasAccess) return emptyList()
        val bookmarks = runCatching { Settings.BOOKMARK_DIRECTORIES.valueCompat }.getOrDefault(emptyList())
            .filter { it.path.isLinuxPath }
        val bases = OrdoDocumentIds.bases(bookmarks.map { File(it.path.toString()) })
        return bases.map { base ->
            base to (bookmarks.firstOrNull { File(it.path.toString()).canonicalPath == base.path }?.name ?: base.name)
        }
    }

    private fun bases() = favourites().map { it.first }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_PROJECTION)
        val context = context ?: return cursor
        if (favourites().isEmpty()) return cursor
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, OrdoDocumentIds.ROOT)
            add(Root.COLUMN_TITLE, context.getString(R.string.app_name))
            add(Root.COLUMN_SUMMARY, context.getString(R.string.documents_root_summary))
            add(Root.COLUMN_ICON, R.mipmap.launcher_icon)
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_MIME_TYPES, "*/*")
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        if (documentId == OrdoDocumentIds.ROOT) {
            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, OrdoDocumentIds.ROOT)
                add(Document.COLUMN_DISPLAY_NAME, context!!.getString(R.string.documents_root_summary))
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_FLAGS, 0)
                add(Document.COLUMN_SIZE, null)
                add(Document.COLUMN_LAST_MODIFIED, null)
            }
        } else {
            addFile(cursor, fileOf(documentId), null)
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        if (parentDocumentId == OrdoDocumentIds.ROOT) {
            favourites().forEach { (base, name) -> addFile(cursor, base, name) }
        } else {
            val directory = fileOf(parentDocumentId)
            directory.listFiles()?.filter { !it.isHidden }?.forEach { addFile(cursor, it, null) }
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        // Read-only: whatever the caller asks for, Ordo never lets another app write here.
        if (mode != "r") throw SecurityException("Ordo's documents are read-only")
        val file = fileOf(documentId)
        if (file.isDirectory) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val bases = bases()
        val child = OrdoDocumentIds.fileOf(documentId, bases) ?: return false
        if (parentDocumentId == OrdoDocumentIds.ROOT) return true
        val parent = OrdoDocumentIds.fileOf(parentDocumentId, bases) ?: return false
        return OrdoDocumentIds.isInside(child, parent)
    }

    override fun getDocumentType(documentId: String): String =
        if (documentId == OrdoDocumentIds.ROOT) Document.MIME_TYPE_DIR else mimeTypeOf(fileOf(documentId))

    private fun fileOf(documentId: String): File =
        OrdoDocumentIds.fileOf(documentId, bases()) ?: throw FileNotFoundException(documentId)

    private fun addFile(cursor: MatrixCursor, file: File, name: String?) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, OrdoDocumentIds.idOf(file))
            add(Document.COLUMN_DISPLAY_NAME, name ?: file.name)
            add(Document.COLUMN_MIME_TYPE, mimeTypeOf(file))
            add(Document.COLUMN_FLAGS, 0)
            add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        }
    }

    private fun mimeTypeOf(file: File): String =
        if (file.isDirectory) {
            Document.MIME_TYPE_DIR
        } else {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                ?: "application/octet-stream"
        }

    companion object {
        private const val ROOT_ID = "ordo"

        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_ICON,
            Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES
        )
        private val DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
        )
    }
}
