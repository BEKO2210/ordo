/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.Collections
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.guessFromPath
import me.zhanghai.android.files.vault.Vault
import me.zhanghai.android.files.vault.VaultEntry
import me.zhanghai.android.files.viewer.video.VideoPlayerActivity

/**
 * Videos of Ordo's Tresor in Ordo's video player, decrypted while they stream ([VaultDataSource]); no plaintext
 * file, no resume place, no thumbnail. Only entries the unlocked vault screen handed over ([grant]) can be read, and
 * locking the vault takes that back ([revokeAll]); after the process ends nothing is granted.
 *
 * The file name never leaves the vault screen: the player's item has an opaque ID and a generic title ([item]), so
 * the MediaSession (dumpsys, lock screen, notification listeners, Bluetooth AVRCP) only ever sees "Vault video".
 * A Tresor queue must never reach a background service, notification or picture-in-picture: the player closes
 * when left (VideoPlayerActivity.onStop) — keep that guard if background playback (#100) comes.
 */
object VaultVideos {
    const val SCHEME = "ordo-vault"

    // Granted entry IDs with their MIME type (the player's format hint, instead of an extension in the URI).
    private val granted: MutableMap<String, String?> = Collections.synchronizedMap(HashMap())

    /** `ordo-vault:///<id>`: opaque, no name and no extension. */
    fun uri(id: String): String = "$SCHEME:///$id"

    /**
     * The player's item for a Tresor video without its URI (the caller adds `setUri`): opaque media ID, [title] the
     * generic "Vault video", no other metadata and no artwork, the format only as [mimeType].
     */
    fun item(id: String, mimeType: String?, title: String): MediaItem.Builder =
        MediaItem.Builder()
            .setMediaId(uri(id))
            .setMimeType(mimeType)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setDisplayTitle(title).setIsPlayable(true).build())

    /** [item] with its URI, for a media ID from [uri]; the MIME type comes from the grant. */
    fun mediaItem(mediaId: String, title: String): MediaItem {
        val id = Uri.parse(mediaId).pathSegments.firstOrNull().orEmpty()
        return item(id, granted[id], title).setUri(Uri.parse(mediaId)).build()
    }

    fun isVault(uri: String?): Boolean = uri?.startsWith("$SCHEME:") == true

    internal val channels = OpenChannels()

    /** Locking the vault: no new reads, and every channel still open closes now, so playback stops at once. */
    fun revokeAll() {
        granted.clear()
        channels.revoke()
    }

    /** Plays [entries] (the vault's videos, in its order) from [start]; the vault must be unlocked. */
    fun play(context: Context, entries: List<VaultEntry>, start: VaultEntry) {
        entries.forEach { granted[it.id] = MimeType.guessFromPath(it.name).value }
        val intent = Intent(context, VideoPlayerActivity::class.java)
        VideoPlayerActivity.putVaultExtras(
            intent, entries.map { uri(it.id) }, entries.indexOf(start).coerceAtLeast(0)
        )
        context.startActivity(intent)
    }

    internal fun idOf(uri: Uri): String? = uri.pathSegments.firstOrNull()?.takeIf { it in granted.keys }

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

/**
 * The decrypting channels open under the current grant. [revoke] closes them all; one opened while a revoke ran
 * (its [generation] read before) is closed by [add] instead of slipping through.
 */
internal class OpenChannels {
    private val open = HashSet<Closeable>()
    private var generation = 0

    @Synchronized
    fun generation(): Int = generation

    /** Tracks [channel]; false, and the channel closed, when a revoke came after [generation] was read. */
    @Synchronized
    fun add(channel: Closeable, generation: Int): Boolean {
        if (generation != this.generation) {
            runCatching { channel.close() }
            return false
        }
        open += channel
        return true
    }

    @Synchronized
    fun remove(channel: Closeable) {
        open -= channel
    }

    @Synchronized
    fun count(): Int = open.size

    fun revoke() {
        val closing = synchronized(this) {
            generation++
            open.toList().also { open.clear() }
        }
        // Closed outside the lock: a read blocked in the channel must not hold up the revoke.
        closing.forEach { runCatching { it.close() } }
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
        // Read before the grant check: a revoke in between closes the channel in add() below.
        val generation = VaultVideos.channels.generation()
        val id = VaultVideos.idOf(dataSpec.uri)
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        val newChannel = Vault.store.openSeekable(id)
        if (!VaultVideos.channels.add(newChannel, generation)) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        }
        try {
            val size = newChannel.size()
            if (dataSpec.position > size) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
            newChannel.position(dataSpec.position)
            bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) size - dataSpec.position
            else minOf(dataSpec.length, size - dataSpec.position)
        } catch (e: Throwable) {
            VaultVideos.channels.remove(newChannel)
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
            channel?.let {
                VaultVideos.channels.remove(it)
                it.close()
            }
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
