/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.cast

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlayerTransferState
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.security.cert.CertificateException
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.readAttributes

/**
 * Moves the playback service's session between the phone and a cast device. The service hands over its session
 * once ([attach]); the player screen and the cast chooser call [connect] and [disconnect]. While casting, the local
 * ExoPlayer stays paused, so its effects (equalizer, Klang, ReplayGain, mono, sleep fade) simply do not apply.
 */
@UnstableApi
@MainThread
object CastManager {
    enum class Phase { IDLE, CONNECTING, CASTING }

    var phase = Phase.IDLE
        private set
    var device: CastDevice? = null
        private set
    /** A known name that now shows another device; the chooser asks before [connect] with `confirmed = true`. */
    var confirmation: CastDevice? = null
        private set

    private val listeners = mutableSetOf<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var session: MediaSession? = null
    private var localPlayer: Player? = null
    private var client: CastClient? = null
    private var server: CastFileServer? = null
    private var castPlayer: CastPlayer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var attempt = 0

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** Called by the playback service once its session exists. */
    fun attach(context: Context, session: MediaSession) {
        this.context = context.applicationContext
        this.session = session
        localPlayer = session.player
    }

    /** Called when the playback service goes away: casting ends with it. */
    fun detach() {
        end(stopReceiver = true)
        session = null
        localPlayer = null
    }

    /** True when this name was seen before as another device ID: the chooser marks it. */
    fun isUnfamiliar(target: CastDevice): Boolean {
        val remembered = context?.getSharedPreferences(PINS, Context.MODE_PRIVATE)?.getString(target.name, null)
            ?: return false
        return !remembered.startsWith(CastDeviceAuth.record(target.id, ""))
    }

    fun dismissConfirmation() {
        confirmation = null
        setPhase(phase)
    }

    fun connect(target: CastDevice, confirmed: Boolean = false) {
        val context = context ?: return
        confirmation = null
        if (phase != Phase.IDLE) end(stopReceiver = device?.id != target.id)
        if (phase != Phase.IDLE) return
        val currentAttempt = ++attempt
        device = target
        setPhase(Phase.CONNECTING)
        val pins = context.getSharedPreferences(PINS, Context.MODE_PRIVATE)
        val newClient = CastClient(target.host, target.port, object : CastClient.Listener {
            override fun onReceiverStatus(status: CastProtocol.ReceiverStatus) {
                castPlayer?.onReceiverStatus(status)
            }

            override fun onMediaStatus(status: CastProtocol.MediaStatus) {
                castPlayer?.onMediaStatus(status)
            }

            override fun onRequestFailed(type: String, requestId: Int) {
                castPlayer?.onRequestFailed(requestId)
            }

            override fun onClosed(error: Throwable?) {
                if (error != null) handler.post { if (attempt == currentAttempt) end(stopReceiver = false, lost = true) }
            }
        }, listOfNotNull(OWN_RECEIVER, CastProtocol.DEFAULT_MEDIA_RECEIVER))
        client = newClient
        Thread({
            val result = runCatching {
                val pin = newClient.connect().pin
                // Keyed by the name people pick from, so a stranger with a familiar name never slips through.
                val known = CastDeviceAuth.compare(pins.getString(target.name, null), target.id, pin)
                if (known == CastDeviceAuth.Known.CHANGED && !confirmed) throw IdentityChanged()
                pins.edit().putString(target.name, CastDeviceAuth.record(target.id, pin)).apply()
                // Ordo's own TV screen; Google's Default Media Receiver whenever it cannot start, so casting never breaks.
                val receiver = OWN_RECEIVER?.let { runCatching { newClient.launch(it, OWN_RECEIVER_TIMEOUT_MS) }.getOrNull() }
                    ?: newClient.launch(CastProtocol.DEFAULT_MEDIA_RECEIVER)
                val newServer = CastFileServer(newClient.localAddress, InetAddress.getByName(target.host))
                newServer to receiver
            }
            handler.post {
                if (attempt != currentAttempt) {
                    result.getOrNull()?.first?.close()
                    return@post
                }
                result.onSuccess { (newServer, receiver) -> startCasting(newServer, receiver) }
                    .onFailure {
                        newClient.close()
                        client = null
                        device = null
                        if (it is IdentityChanged) confirmation = target
                        setPhase(Phase.IDLE)
                        when (it) {
                            is IdentityChanged -> {}
                            is CertificateException -> toast(R.string.cast_error_identity, target.name)
                            else -> toast(R.string.cast_error_connect, target.name)
                        }
                    }
            }
        }, "cast-connect").start()
    }

    /** Back to the phone: the TV's app is closed and the queue continues here, paused, where the TV was. */
    fun disconnect() = end(stopReceiver = true)

    private fun startCasting(newServer: CastFileServer, receiver: CastProtocol.ReceiverStatus) {
        val session = session
        val local = localPlayer
        val client = client
        if (session == null || local == null || client == null) {
            newServer.close()
            end(stopReceiver = true)
            return
        }
        server = newServer
        val transfer = PlayerTransferState.fromPlayer(local)
        val current = local.mediaMetadata
        local.pause()
        val player = CastPlayer(client) { item -> prepare(item, newServer) }
        castPlayer = player
        val timeline = local.currentTimeline
        val shuffleOrder = if (local.shuffleModeEnabled && !timeline.isEmpty) {
            CastPlayback.traversal(timeline.getFirstWindowIndex(true)) {
                timeline.getNextWindowIndex(it, Player.REPEAT_MODE_OFF, true)
            }
        } else null
        player.start(transfer, current, receiver.volumeLevel, receiver.muted, shuffleOrder)
        session.player = player
        acquireLocks()
        setPhase(Phase.CASTING)
    }

    private fun end(stopReceiver: Boolean, lost: Boolean = false) {
        if (phase == Phase.IDLE) return
        attempt++
        val lostDevice = device
        castPlayer?.let { player ->
            val local = localPlayer
            val session = session
            if (local != null && session != null) {
                val (transfer, shuffleOrder) = player.handBack()
                session.player = local
                transfer.setToPlayer(local)
                // setMediaItems drew a new random order; put back the one the TV was playing.
                val exoPlayer = (local as? ForwardingPlayer)?.wrappedPlayer as? ExoPlayer
                if (shuffleOrder != null && exoPlayer != null && shuffleOrder.size == exoPlayer.mediaItemCount) {
                    exoPlayer.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(shuffleOrder, System.nanoTime()))
                }
                local.prepare()
            }
            player.release()
        }
        castPlayer = null
        val oldClient = client
        val oldServer = server
        client = null
        server = null
        Thread({
            if (stopReceiver && oldClient != null) {
                oldClient.stopApp()
                oldClient.flush()
            }
            oldClient?.close()
            oldServer?.close()
        }, "cast-end").start()
        releaseLocks()
        device = null
        setPhase(Phase.IDLE)
        if (lost && lostDevice != null) toast(R.string.cast_error_lost, lostDevice.name)
    }

    private fun setPhase(newPhase: Phase) {
        phase = newPhase
        listeners.toList().forEach { it() }
    }

    private fun toast(message: Int, name: String) {
        context?.let { Toast.makeText(it, it.getString(message, name), Toast.LENGTH_LONG).show() }
    }

    // Casting means the phone serves the file over Wi-Fi with the screen off; ExoPlayer's own wake mode does not
    // apply because ExoPlayer is not playing.
    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val context = context ?: return
        @Suppress("DEPRECATION") // FULL_HIGH_PERF keeps Wi-Fi out of power save; its successor needs API 29.
        wifiLock = context.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Ordo:cast")
            ?.apply { setReferenceCounted(false); acquire() }
        wakeLock = context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ordo:cast")
            ?.apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        wakeLock = null
    }

    /** Background thread: puts the item's file (and cover) on the server and reads its tags for the TV. */
    private fun prepare(item: MediaItem, server: CastFileServer): CastPlayer.Prepared {
        val context = checkNotNull(context)
        val uri = item.localConfiguration?.uri ?: Uri.parse(item.mediaId)
        val name: String
        val source: CastFileServer.Source
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val resolver = context.contentResolver
            var displayName = uri.lastPathSegment.orEmpty()
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) {
                    it.getString(0)?.let { value -> displayName = value }
                    if (!it.isNull(1)) size = it.getLong(1)
                }
            }
            if (size < 0) size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            // The receiver seeks with ranges, which need the size up front.
            if (size < 0) throw java.io.IOException("Unknown file size")
            name = displayName
            source = CastFileServer.Source(size, "", open = { offset ->
                val input = checkNotNull(resolver.openInputStream(uri))
                try {
                    input.skipFully(offset)
                    input
                } catch (e: Exception) {
                    runCatching { input.close() }
                    throw e
                }
            })
        } else {
            val path = Paths.get(URI.create(item.mediaId))
            name = path.fileName?.toString().orEmpty()
            source = CastFileServer.Source(path.readAttributes(BasicFileAttributes::class.java).size(), "", open = { offset ->
                val channel = path.newByteChannel(setOf(StandardOpenOption.READ))
                try {
                    channel.position(offset)
                    ChannelInputStream(channel)
                } catch (e: Exception) {
                    runCatching { channel.close() }
                    throw e
                }
            })
        }
        val tags = readTags(uri, item, context)
        val contentType = CastPlayback.contentType(name) ?: tags.mimeType ?: "audio/mpeg"
        val track = CastFileServer.Source(source.size, contentType, source.open)
        // The TV gets a JPEG of at most 1024 px: the embedded cover, else the album's cover picture in the folder.
        val coverJpeg = (tags.picture ?: if (uri.scheme == ContentResolver.SCHEME_CONTENT) null else folderCover(item))
            ?.let(::castCover)
        val cover = coverJpeg?.let { jpeg ->
            CastFileServer.Source(jpeg.size.toLong(), "image/jpeg") { offset ->
                ByteArrayInputStream(jpeg, offset.toInt(), jpeg.size - offset.toInt())
            }
        }
        val (url, coverUrl) = server.serve(track, cover)
        val title = tags.title ?: item.mediaMetadata.displayTitle?.toString() ?: name
        val metadata = item.mediaMetadata.buildUpon()
            .setTitle(tags.title ?: title)
            .setDisplayTitle(title)
            .setArtist(tags.artist)
            .setAlbumTitle(tags.album)
            .apply { tags.picture?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
            .build()
        return CastPlayer.Prepared(
            CastProtocol.Track(url, contentType, title, tags.artist, tags.album, coverUrl, tags.albumArtist, tags.trackNumber),
            metadata
        )
    }

    private class Tags(
        val title: String?,
        val artist: String?,
        val album: String?,
        val mimeType: String?,
        val picture: ByteArray?,
        val albumArtist: String? = null,
        val trackNumber: Int? = null
    )

    private fun readTags(uri: Uri, item: MediaItem, context: Context): Tags {
        val retriever = MediaMetadataRetriever()
        var dataSource: PathMediaDataSource? = null
        return try {
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                retriever.setDataSource(context, uri)
            } else {
                dataSource = PathMediaDataSource(Paths.get(URI.create(item.mediaId)))
                retriever.setDataSource(dataSource)
            }
            fun text(key: Int) = retriever.extractMetadata(key)?.takeIf { it.isNotBlank() }
            Tags(
                text(MediaMetadataRetriever.METADATA_KEY_TITLE),
                text(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                text(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                text(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                retriever.embeddedPicture,
                text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                CastPlayback.trackNumber(text(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER))
            )
        } catch (_: Exception) {
            Tags(null, null, null, null, null)
        } finally {
            runCatching { retriever.release() }
            runCatching { dataSource?.close() }
        }
    }

    private fun folderCover(item: MediaItem): ByteArray? = runCatching {
        val folder = Paths.get(URI.create(item.mediaId)).parent ?: return null
        CastPlayback.FOLDER_COVERS.firstNotNullOfOrNull { name ->
            runCatching { folder.resolve(name).newInputStream().use { it.readBytes() } }.getOrNull()
        }
    }.getOrNull()

    /** Decodes, scales to at most 1024 px and re-encodes as JPEG; null for anything that is not a picture. */
    private fun castCover(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= CastPlayback.COVER_MAX_PX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val (width, height) = CastPlayback.coverSize(decoded.width, decoded.height)
        val scaled = if (width == decoded.width) decoded else Bitmap.createScaledBitmap(decoded, width, height, true)
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
            out.toByteArray()
        }
    }

    private fun InputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) {
                if (read() < 0) return
                left--
            } else {
                left -= skipped
            }
        }
    }

    private class ChannelInputStream(private val channel: SeekableByteChannel) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = if (len == 0) 0 else channel.read(ByteBuffer.wrap(b, off, len))

        override fun close() = channel.close()
    }

    /** Lets MediaMetadataRetriever read tags from any Ordo path (SMB, SFTP, …), not only local files. */
    private class PathMediaDataSource(path: Path) : MediaDataSource() {
        private val channel = path.newByteChannel(setOf(StandardOpenOption.READ))
        private val size = channel.size()

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= this.size) return -1
            channel.position(position)
            return channel.read(ByteBuffer.wrap(buffer, offset, size))
        }

        override fun getSize(): Long = size

        override fun close() = channel.close()
    }

    private class IdentityChanged : Exception()

    /** Ordo's Custom Web Receiver (docs/cast/index.html), registered in the Cast console; empty = not configured. */
    private val OWN_RECEIVER = BuildConfig.ORDO_CAST_APP_ID.takeIf { it.isNotEmpty() }
    private const val OWN_RECEIVER_TIMEOUT_MS = 12_000L

    private const val PINS = "cast_device_pins"
}
