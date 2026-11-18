/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.documents

import android.Manifest
import android.content.res.AssetFileDescriptor
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.media.MediaMetadataRetriever
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import me.zhanghai.android.files.R
import me.zhanghai.android.files.home.HomeRepository
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.supportsExternalStorageManager
import me.zhanghai.android.files.util.valueCompat

/**
 * Ordo as a place in Android's own file picker (Storage Access Framework), read-only: "Ordo – Your bookmarks" with the
 * user's bookmark folders, and "Ordo – Recently used" with the files home lists as recent (at most 50), each hidden
 * while empty. Another app gets only what the user picks, read-only. Nothing without all-files access, nothing
 * outside those folders and files, never app-private folders or the vault. Only the system may call it
 * (android.permission.MANAGE_DOCUMENTS in the manifest).
 */
class OrdoDocumentsProvider : DocumentsProvider() {
    private var recentCache: Pair<Long, List<File>>? = null

    override fun onCreate(): Boolean {
        val context = context ?: return false
        // Posted: providers start before the application, and Settings needs it. A changed bookmark list re-reads
        // the places in an open picker.
        Handler(Looper.getMainLooper()).post {
            val rootsUri = DocumentsContract.buildRootsUri(context.packageName + AUTHORITY_SUFFIX)
            Settings.BOOKMARK_DIRECTORIES.observeForever { context.contentResolver.notifyChange(rootsUri, null) }
        }
        return true
    }

    private fun hasAccess(): Boolean {
        val context = context ?: return false
        return if (Environment::class.supportsExternalStorageManager()) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun favourites(): List<Pair<File, String>> {
        if (!hasAccess()) return emptyList()
        val bookmarks = runCatching { Settings.BOOKMARK_DIRECTORIES.valueCompat }.getOrDefault(emptyList())
            .filter { it.path.isLinuxPath }
        val bases = OrdoDocumentIds.bases(bookmarks.map { File(it.path.toString()) })
        return bases.map { base ->
            base to (bookmarks.firstOrNull { File(it.path.toString()).canonicalPath == base.path }?.name ?: base.name)
        }
    }

    private fun bases() = favourites().map { it.first }

    /** The recent files, read from MediaStore at most every [RECENT_CACHE_MS]: the picker asks many times a second. */
    @Synchronized
    private fun recent(): List<File> {
        if (!hasAccess()) return emptyList()
        val now = SystemClock.elapsedRealtime()
        recentCache?.let { (time, files) -> if (now - time < RECENT_CACHE_MS) return files }
        val files = runCatching {
            OrdoDocumentIds.recentFiles(HomeRepository(context!!).loadRecentFiles(OrdoDocumentIds.RECENT_LIMIT))
        }.getOrDefault(emptyList())
        recentCache = now to files
        return files
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_PROJECTION)
        val context = context ?: return cursor
        fun addRoot(rootId: String, documentId: String, summary: Int) {
            cursor.newRow().apply {
                add(Root.COLUMN_ROOT_ID, rootId)
                add(Root.COLUMN_DOCUMENT_ID, documentId)
                add(Root.COLUMN_TITLE, context.getString(R.string.app_name))
                add(Root.COLUMN_SUMMARY, context.getString(summary))
                add(Root.COLUMN_ICON, R.mipmap.launcher_icon)
                add(Root.COLUMN_FLAGS, if (OrdoDocumentIds.supportsTree(documentId)) Root.FLAG_SUPPORTS_IS_CHILD else 0)
                add(Root.COLUMN_MIME_TYPES, "*/*")
            }
        }
        if (favourites().isNotEmpty()) addRoot(ROOT_ID, OrdoDocumentIds.ROOT, R.string.documents_root_summary)
        if (recent().isNotEmpty()) addRoot(RECENT_ROOT_ID, OrdoDocumentIds.RECENT, R.string.documents_recent_summary)
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        val topName = when (documentId) {
            OrdoDocumentIds.ROOT -> R.string.documents_root_summary
            OrdoDocumentIds.RECENT -> R.string.documents_recent_summary
            else -> null
        }
        if (topName != null) {
            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, documentId)
                add(Document.COLUMN_DISPLAY_NAME, context!!.getString(topName))
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
        when (parentDocumentId) {
            OrdoDocumentIds.ROOT -> favourites().forEach { (base, name) -> addFile(cursor, base, name) }
            OrdoDocumentIds.RECENT -> recent().forEach { addFile(cursor, it, null) }
            else -> fileOf(parentDocumentId).listFiles()?.filter { !it.isHidden }?.forEach { addFile(cursor, it, null) }
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

    /**
     * A small JPEG of a picture (decoded subsampled, never in full) or of a song's embedded cover, at most
     * [THUMBNAIL_MAX_PX] on its shorter side; files above [THUMBNAIL_MAX_BYTES] get none.
     */
    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point?,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        val file = fileOf(documentId)
        val target = minOf(sizeHint?.let { minOf(it.x, it.y) } ?: THUMBNAIL_MAX_PX, THUMBNAIL_MAX_PX)
        if (file.length() > THUMBNAIL_MAX_BYTES) throw FileNotFoundException(documentId)
        val mimeType = mimeTypeOf(file)
        val bitmap = try {
            when {
                mimeType.startsWith("image/") -> decodeSampled(target) { BitmapFactory.decodeFile(file.path, it) }
                mimeType.startsWith("audio/") -> {
                    val picture = MediaMetadataRetriever().run {
                        try {
                            setDataSource(file.path)
                            embeddedPicture
                        } finally {
                            release()
                        }
                    } ?: throw FileNotFoundException(documentId)
                    decodeSampled(target) { BitmapFactory.decodeByteArray(picture, 0, picture.size, it) }
                }
                else -> null
            }
        } catch (e: OutOfMemoryError) {
            null
        } ?: throw FileNotFoundException(documentId)
        signal?.throwIfCanceled()
        val thumbnail = File(context!!.cacheDir, "document-thumbnails").apply { mkdirs() }
            .let { File(it, documentId.hashCode().toUInt().toString() + ".jpg") }
        thumbnail.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        return AssetFileDescriptor(
            ParcelFileDescriptor.open(thumbnail, ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH
        )
    }

    private fun decodeSampled(target: Int, decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        if (!OrdoDocumentIds.canThumbnail(bounds.outWidth, bounds.outHeight)) return null
        return decode(BitmapFactory.Options().apply {
            inSampleSize = OrdoDocumentIds.sampleSize(bounds.outWidth, bounds.outHeight, target)
        })
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        // No tree grants on "Recently used" (see OrdoDocumentIds.supportsTree).
        if (parentDocumentId == OrdoDocumentIds.RECENT) return false
        val bases = bases()
        val child = OrdoDocumentIds.fileOf(documentId, bases) ?: return false
        if (parentDocumentId == OrdoDocumentIds.ROOT) return true
        val parent = OrdoDocumentIds.fileOf(parentDocumentId, bases) ?: return false
        return OrdoDocumentIds.isInside(child, parent)
    }

    override fun getDocumentType(documentId: String): String =
        if (documentId == OrdoDocumentIds.ROOT || documentId == OrdoDocumentIds.RECENT) {
            Document.MIME_TYPE_DIR
        } else {
            mimeTypeOf(fileOf(documentId))
        }

    private fun fileOf(documentId: String): File =
        OrdoDocumentIds.fileOf(documentId, bases(), recent()) ?: throw FileNotFoundException(documentId)

    private fun addFile(cursor: MatrixCursor, file: File, name: String?) {
        val mimeType = mimeTypeOf(file)
        val thumbnail = mimeType.startsWith("image/") || mimeType.startsWith("audio/")
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, OrdoDocumentIds.idOf(file))
            add(Document.COLUMN_DISPLAY_NAME, name ?: file.name)
            add(Document.COLUMN_MIME_TYPE, mimeType)
            add(Document.COLUMN_FLAGS, if (thumbnail) Document.FLAG_SUPPORTS_THUMBNAIL else 0)
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
        private const val AUTHORITY_SUFFIX = ".documents"
        private const val ROOT_ID = "ordo"
        private const val RECENT_ROOT_ID = "ordo_recent"
        private const val RECENT_CACHE_MS = 10_000L
        private const val THUMBNAIL_MAX_PX = 512
        private const val THUMBNAIL_MAX_BYTES = 64L * 1024 * 1024

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
