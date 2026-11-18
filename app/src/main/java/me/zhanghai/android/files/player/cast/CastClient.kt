/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.cast

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * A Cast V2 sender for one device: TLS on port 8009, device auth with a pinned device certificate, heartbeat,
 * the Default Media Receiver and its media session. Plain JVM code, so the same class runs in the app and in the
 * check against a real Chromecast. Listener calls come from the reader thread.
 */
class CastClient(
    private val host: String,
    private val port: Int = 8009,
    private val listener: Listener
) : Closeable {
    interface Listener {
        fun onReceiverStatus(status: CastProtocol.ReceiverStatus) {}
        fun onMediaStatus(status: CastProtocol.MediaStatus) {}
        /** LOAD_FAILED, LOAD_CANCELLED or INVALID_REQUEST for one of our requests. */
        fun onRequestFailed(type: String, requestId: Int) {}
        /** The connection or the receiver app ended; [error] is null after [close]. */
        fun onClosed(error: Throwable?) {}
    }

    private lateinit var socket: SSLSocket
    private lateinit var output: BufferedOutputStream
    private val writer = Executors.newSingleThreadScheduledExecutor { Thread(it, "cast-writer") }
    private val requestIds = AtomicInteger(1)
    private val pendingLaunch = LinkedBlockingQueue<CastProtocol.ReceiverStatus>()
    private val pendingAuth = LinkedBlockingQueue<ByteArray>()
    @Volatile private var lastReceivedMs = 0L
    @Volatile private var closed = false
    @Volatile var transportId: String? = null
        private set
    @Volatile var appSessionId: String? = null
        private set
    @Volatile var mediaSessionId: Int? = null
        private set

    /** The address of this end of the connection: the network interface the device can reach us on. */
    val localAddress: InetAddress get() = socket.localAddress

    /**
     * Connects and proves the device is a real Cast device ([CastDeviceAuth.verify]); throws
     * [java.security.cert.CertificateException] otherwise. Blocking; not on the main thread.
     */
    fun connect(timeoutMs: Int = 8_000): CastDeviceAuth.Device {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(CastDeviceAuth.TlsTrustManager()), SecureRandom())
        socket = context.socketFactory.createSocket() as SSLSocket
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        // A sleeping Chromecast accepted TCP but never finished TLS (17.11.2026): without a timeout this hung forever.
        socket.soTimeout = timeoutMs
        socket.startHandshake()
        // From here on the heartbeat notices silence and closes the socket.
        socket.soTimeout = 0
        output = BufferedOutputStream(socket.outputStream)
        lastReceivedMs = System.currentTimeMillis()
        Thread(::readLoop, "cast-reader").apply { isDaemon = true }.start()
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sendNow(CastMessage(CastProtocol.SENDER_ID, CastProtocol.RECEIVER_ID, CastDeviceAuth.NAMESPACE, "",
            CastDeviceAuth.challenge(nonce)))
        val device = try {
            val reply = pendingAuth.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                ?: throw IOException("No device auth reply")
            CastDeviceAuth.verify(reply, nonce, socket.session.peerCertificates[0].encoded)
        } catch (e: Exception) {
            close()
            throw e
        }
        sendNow(message(CastProtocol.RECEIVER_ID, CastProtocol.NS_CONNECTION, CastProtocol.type("CONNECT")))
        writer.scheduleWithFixedDelay(::heartbeat, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS)
        return device
    }

    /** Starts (or joins) the Default Media Receiver and opens a virtual connection to it. Blocking. */
    fun launch(timeoutMs: Long = 20_000): CastProtocol.ReceiverStatus {
        sendNow(message(CastProtocol.RECEIVER_ID, CastProtocol.NS_RECEIVER, CastProtocol.launch(nextRequestId())))
        val status = pendingLaunch.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw IOException("The device did not start the media receiver")
        sendNow(message(status.transportId!!, CastProtocol.NS_CONNECTION, CastProtocol.type("CONNECT")))
        return status
    }

    fun load(track: CastProtocol.Track, startSeconds: Double, autoplay: Boolean): Int =
        nextRequestId().also { media(CastProtocol.load(it, track, startSeconds, autoplay)) }

    fun play() = mediaCommand("PLAY")
    fun pause() = mediaCommand("PAUSE")
    fun stopMedia() = mediaCommand("STOP")
    fun requestMediaStatus() = mediaCommand("GET_STATUS")

    fun seek(seconds: Double) {
        val session = mediaSessionId ?: return
        media(CastProtocol.seek(nextRequestId(), session, seconds))
    }

    fun setVolume(level: Double? = null, muted: Boolean? = null) =
        send(message(CastProtocol.RECEIVER_ID, CastProtocol.NS_RECEIVER, CastProtocol.setVolume(nextRequestId(), level, muted)))

    /** Closes the receiver app on the device, so the TV goes back to where it was. */
    fun stopApp() {
        val session = appSessionId ?: return
        send(message(CastProtocol.RECEIVER_ID, CastProtocol.NS_RECEIVER, CastProtocol.stopApp(nextRequestId(), session)))
    }

    /** Waits until everything queued so far is written; for the end of a session. */
    fun flush(timeoutMs: Long = 3_000) {
        runCatching { writer.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    override fun close() = shutdown(null)

    private fun shutdown(error: Throwable?) {
        if (closed) return
        closed = true
        writer.shutdownNow()
        if (::socket.isInitialized) runCatching { socket.close() }
        listener.onClosed(error)
    }

    private fun mediaCommand(type: String) {
        val session = mediaSessionId ?: return
        media(CastProtocol.media(type, nextRequestId(), session))
    }

    private fun media(payload: String) {
        val transport = transportId ?: return
        send(message(transport, CastProtocol.NS_MEDIA, payload))
    }

    private fun nextRequestId() = requestIds.getAndIncrement()

    private fun message(destination: String, namespace: String, payload: String) =
        CastMessage(CastProtocol.SENDER_ID, destination, namespace, payload)

    private fun send(message: CastMessage) {
        if (closed) return
        runCatching { writer.execute { runCatching { sendNow(message) }.onFailure(::shutdown) } }
    }

    private fun sendNow(message: CastMessage) = synchronized(this) { CastMessage.write(output, message) }

    private fun heartbeat() {
        if (System.currentTimeMillis() - lastReceivedMs > HEARTBEAT_TIMEOUT_MS) {
            shutdown(IOException("The device stopped answering"))
            return
        }
        runCatching {
            sendNow(message(CastProtocol.RECEIVER_ID, CastProtocol.NS_HEARTBEAT, CastProtocol.type("PING")))
        }.onFailure(::shutdown)
    }

    private fun readLoop() {
        try {
            val input = DataInputStream(socket.inputStream.buffered())
            while (!closed) {
                val message = CastMessage.read(input)
                lastReceivedMs = System.currentTimeMillis()
                handle(message)
            }
        } catch (e: Exception) {
            shutdown(e)
        }
    }

    private fun handle(message: CastMessage) {
        if (message.namespace == CastDeviceAuth.NAMESPACE) {
            message.binary?.let { pendingAuth.offer(it) }
            return
        }
        val type = CastProtocol.typeOf(message.payload)
        when (message.namespace) {
            CastProtocol.NS_HEARTBEAT -> if (type == "PING") {
                send(CastMessage(CastProtocol.SENDER_ID, message.sourceId, CastProtocol.NS_HEARTBEAT, CastProtocol.type("PONG")))
            }
            CastProtocol.NS_CONNECTION -> if (type == "CLOSE" && message.sourceId == transportId) {
                shutdown(IOException("The receiver closed the connection"))
            }
            CastProtocol.NS_RECEIVER -> {
                val status = CastProtocol.parseReceiverStatus(message.payload) ?: return
                val wasRunning = transportId != null
                transportId = status.transportId
                appSessionId = status.sessionId
                if (status.transportId != null && !wasRunning) pendingLaunch.offer(status)
                listener.onReceiverStatus(status)
                // Someone else took the TV over, or it was switched to another app.
                if (wasRunning && status.transportId == null) shutdown(IOException("The receiver app stopped"))
            }
            CastProtocol.NS_MEDIA -> when (type) {
                "MEDIA_STATUS" -> CastProtocol.parseMediaStatus(message.payload)?.let {
                    mediaSessionId = it.mediaSessionId
                    listener.onMediaStatus(it)
                }
                "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST" ->
                    listener.onRequestFailed(type, CastProtocol.requestIdOf(message.payload))
            }
        }
    }

    companion object {
        private const val HEARTBEAT_MS = 5_000L
        private const val HEARTBEAT_TIMEOUT_MS = 20_000L
    }
}
