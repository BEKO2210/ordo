/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.Collections
import me.zhanghai.android.files.player.AudioQueueRequests
import me.zhanghai.android.files.vault.Vault
import me.zhanghai.android.files.vault.VaultEntry
import me.zhanghai.android.files.viewer.video.VideoPlayerActivity

/**
 * Videos of Ordo's Tresor in Ordo's video player, decrypted while they stream ([VaultDataSource]); no plaintext
 * file, no resume place, no thumbnail. Only entries the unlocked vault screen handed over ([grant]) can be read, and
 * locking the vault takes that back ([revokeAll]); after the process ends nothing is granted.
 */
object VaultVideos {
    const val SCHEME = "ordo-vault"

    private val granted: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** `ordo-vault:///<id>/<name>`: the name keeps the extension (the player's format guess) and the title. */
    fun uri(entry: VaultEntry): String =
        Uri.Builder().scheme(SCHEME).authority("").appendPath(entry.id).appendPath(entry.name).build().toString()

    fun isVault(uri: String?): Boolean = uri?.startsWith("$SCHEME:") == true

    fun revokeAll() = granted.clear()

    /** Plays [entries] (the vault's videos, in its order) from [start]; the vault must be unlocked. */
    fun play(context: Context, entries: List<VaultEntry>, start: VaultEntry) {
        granted += entries.map { it.id }
        val intent = Intent(context, VideoPlayerActivity::class.java)
        VideoPlayerActivity.putVaultExtras(intent, entries.map(::uri), entries.indexOf(start).coerceAtLeast(0))
        context.startActivity(intent)
    }

    internal fun idOf(uri: Uri): String? = uri.pathSegments.firstOrNull()?.takeIf { it in granted }

    /** Routes `ordo-vault:` to [VaultDataSource] and everything else to [upstream]. */
    @UnstableApi
    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = Routing(upstream)
    }

    @UnstableApi
    private class Routing(private val upstream: DataSource.Factory) : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var delegate: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            val source = if (dataSpec.uri.scheme == SCHEME) VaultDataSource() else upstream.createDataSource()
            listeners.forEach(source::addTransferListener)
            delegate = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            checkNotNull(delegate) { "Data source is not open" }.read(buffer, offset, length)

        override fun getUri(): Uri? = delegate?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                delegate?.close()
            } finally {
                delegate = null
            }
        }
    }
}

/** Reads one vault entry through Tink's seekable decrypting channel: segments are decrypted in memory only. */
@UnstableApi
class VaultDataSource : BaseDataSource(false) {
    @Volatile
    private var channel: SeekableByteChannel? = null
    private var bytesRemaining = 0L
    private var uri: Uri? = null
    private var opened = false

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val id = VaultVideos.idOf(dataSpec.uri)
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        val newChannel = Vault.store.openSeekable(id)
        try {
            val size = newChannel.size()
            if (dataSpec.position > size) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
            newChannel.position(dataSpec.position)
            bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) size - dataSpec.position
            else minOf(dataSpec.length, size - dataSpec.position)
        } catch (e: Throwable) {
            newChannel.close()
            throw e
        }
        channel = newChannel
        uri = dataSpec.uri
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val channel = channel ?: throw IOException("Data source is not open")
        val byteBuffer = ByteBuffer.wrap(buffer, offset, minOf(length.toLong(), bytesRemaining).toInt())
        var read: Int
        do {
            read = channel.read(byteBuffer)
        } while (read == 0)
        if (read == -1) throw EOFException("$bytesRemaining bytes missing")
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            channel?.close()
        } finally {
            channel = null
            bytesRemaining = 0
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
