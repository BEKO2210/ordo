/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.StandardOpenOption
import me.zhanghai.android.files.provider.common.newByteChannel

/** Reads any Ordo NIO path with positional, bounded reads. The factory routes `content://` to Media3. */
@UnstableApi
class PathDataSource(isNetwork: Boolean = false) : BaseDataSource(isNetwork) {
    // close() may come from another thread than read().
    @Volatile
    private var channel: SeekableByteChannel? = null
    private var opened = false

    @Volatile
    private var bytesRemaining = 0L
    private var currentUri: Uri? = null

    @Volatile
    private var canceled = false

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val path = try {
            Paths.get(URI.create(dataSpec.uri.toString()))
        } catch (e: Exception) {
            throw IOException("Unsupported path URI: ${dataSpec.uri}", e)
        }
        val length = open(path, dataSpec.position, dataSpec.length)
        currentUri = dataSpec.uri
        opened = true
        transferStarted(dataSpec)
        return length
    }

    /** Kept separate from Android's Uri/DataSpec so channel semantics are covered by local unit tests. */
    @Throws(IOException::class)
    internal fun open(path: Path, position: Long = 0L, length: Long = C.LENGTH_UNSET.toLong()): Long {
        close()
        canceled = false
        val newChannel = path.newByteChannel(setOf(StandardOpenOption.READ))
        try {
            val size = newChannel.size()
            if (position > size) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
            newChannel.position(position)
            channel = newChannel
            bytesRemaining = if (length == C.LENGTH_UNSET.toLong()) {
                size - position
            } else {
                minOf(length, size - position)
            }
            return bytesRemaining
        } catch (e: Throwable) {
            newChannel.close()
            throw e
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        }
        if (bytesRemaining == 0L) {
            return C.RESULT_END_OF_INPUT
        }
        if (canceled) {
            throw IOException("Read canceled")
        }
        val channel = channel ?: throw IOException("Data source is not open")
        val readLength = minOf(length.toLong(), bytesRemaining).toInt()
        val byteBuffer = ByteBuffer.wrap(buffer, offset, readLength)
        val read = try {
            var result: Int
            do {
                if (canceled) throw IOException("Read canceled")
                result = channel.read(byteBuffer)
            } while (result == 0)
            result
        } catch (e: IOException) {
            if (canceled) throw IOException("Read canceled", e) else throw e
        }
        if (read == -1) {
            throw EOFException("Unexpected end of file, $bytesRemaining bytes missing")
        }
        bytesRemaining -= read.toLong()
        if (opened) {
            bytesTransferred(read)
        }
        return read
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        canceled = true
        currentUri = null
        val wasOpened = opened
        opened = false
        try {
            channel?.close()
        } finally {
            channel = null
            bytesRemaining = 0L
            if (wasOpened) {
                transferEnded()
            }
        }
    }

    /** Selects Media3's own content reader only for content URIs; every Ordo path uses this class. */
    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingDataSource(context)
    }

    private companion object {
        val NETWORK_SCHEMES = setOf("sftp", "smb", "ftp", "ftps", "ftpes", "dav", "davs")
    }

    private class RoutingDataSource(private val context: Context) : DataSource {
        private val transferListeners = mutableListOf<TransferListener>()
        private var delegate: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            transferListeners += transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            check(delegate == null) { "Data source is already open" }
            val dataSource = if (dataSpec.uri.scheme == ContentResolver.SCHEME_CONTENT) {
                ContentDataSource(context)
            } else {
                PathDataSource(dataSpec.uri.scheme in NETWORK_SCHEMES)
            }
            transferListeners.forEach(dataSource::addTransferListener)
            delegate = dataSource
            return dataSource.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            checkNotNull(delegate) { "Data source is not open" }.read(buffer, offset, length)

        override fun getUri(): Uri? = delegate?.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            delegate?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                delegate?.close()
            } finally {
                delegate = null
            }
        }
    }
}
