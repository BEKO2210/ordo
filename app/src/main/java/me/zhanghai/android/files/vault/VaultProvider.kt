/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.vault

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.Collections
import java.util.UUID
import java8.nio.file.Path
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.app.storageManager
import me.zhanghai.android.files.compat.ProxyFileDescriptorCallbackCompat
import me.zhanghai.android.files.compat.openProxyFileDescriptorCompat
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.guessFromPath
import me.zhanghai.android.files.viewer.video.vault.OpenChannels

/**
 * A Tresor file for a viewer (Ordo's image and PDF viewer, or another app the user picks), decrypted while it is
 * read: `content://<package>.vault/<token>` hands out a proxy file descriptor whose reads decrypt in memory, so no
 * plaintext copy is ever written. Like [me.zhanghai.android.files.viewer.video.vault.VaultVideos], only entries the
 * unlocked vault screen [grant]ed can be opened, and locking ([revokeAll]) closes every descriptor still open.
 * On Android 7 the compat descriptor is a pipe and cannot seek, so a PDF does not open there.
 */
class VaultProvider : ContentProvider() {
    private lateinit var callbackHandler: Handler

    override fun onCreate(): Boolean {
        // Proxy reads run here, never on a thread that might itself be reading the descriptor.
        callbackHandler = Handler(HandlerThread("VaultProvider.CallbackThread").apply { start() }.looper)
        return true
    }

    override fun getType(uri: Uri): String? =
        grants.entry(uri.lastPathSegment)?.let { MimeType.guessFromPath(it.name).value }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val entry = grants.entry(uri.lastPathSegment)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            if (entry != null) {
                addRow(columns.map {
                    when (it) {
                        OpenableColumns.DISPLAY_NAME -> entry.name
                        OpenableColumns.SIZE -> entry.size
                        else -> null
                    }
                })
            }
        }
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("Vault files are read-only")
        }
        // Read before the grant check: a revoke in between closes the channel in add() below.
        val generation = channels.generation()
        val entry = grants.entry(uri.lastPathSegment) ?: throw FileNotFoundException("Not granted")
        val channel = try {
            Vault.store.openSeekable(entry.id)
        } catch (e: IOException) {
            throw FileNotFoundException(e.toString())
        }
        if (!channels.add(channel, generation)) {
            throw FileNotFoundException("Revoked")
        }
        val callback = object : ProxyFileDescriptorCallbackCompat() {
            override fun onGetSize(): Long = try {
                channel.size()
            } catch (e: IOException) {
                throw ErrnoException("onGetSize", OsConstants.EIO)
            }

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                readAt(channel, offset, data, size)
            } catch (e: IOException) {
                // Also after a lock: revoke closed the channel.
                throw ErrnoException("onRead", OsConstants.EIO)
            }

            override fun onRelease() {
                channels.remove(channel)
                runCatching { channel.close() }
            }
        }
        return try {
            storageManager.openProxyFileDescriptorCompat(
                ParcelFileDescriptor.MODE_READ_ONLY, callback, callbackHandler
            )
        } catch (e: IOException) {
            callback.onRelease()
            throw FileNotFoundException(e.toString())
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        private val grants = VaultGrants()

        private val channels = OpenChannels()

        private val authority: String
            get() = "${application.packageName}.vault"

        /**
         * Lets viewers read [entry] until the vault locks. The URI carries a random token, neither the name nor the
         * entry ID, so an app that kept an Android URI grant from earlier cannot read the entry once it is reopened.
         */
        fun grant(entry: VaultEntry): Uri =
            Uri.Builder().scheme("content").authority(authority).appendPath(grants.grant(entry)).build()

        // A content path prints as its URI.
        fun isVault(path: Path): Boolean = path.toString().startsWith("content://$authority/")

        /** Locking the vault: no new opens, and every descriptor still open fails its next read. */
        fun revokeAll() {
            grants.revokeAll()
            channels.revoke()
        }

        /** Fills [data] with up to [size] bytes from [offset]; fewer only at the end of the file. */
        @Throws(IOException::class)
        internal fun readAt(channel: SeekableByteChannel, offset: Long, data: ByteArray, size: Int): Int {
            channel.position(offset)
            val buffer = ByteBuffer.wrap(data, 0, size)
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
            }
            return buffer.position()
        }
    }
}

/** The entries the unlocked vault screen handed to a viewer, each under a fresh random token. */
internal class VaultGrants {
    private val granted: MutableMap<String, VaultEntry> = Collections.synchronizedMap(HashMap())

    fun grant(entry: VaultEntry): String =
        UUID.randomUUID().toString().also { granted[it] = entry }

    fun entry(token: String?): VaultEntry? = token?.let { granted[it] }

    fun revokeAll() {
        granted.clear()
    }
}
