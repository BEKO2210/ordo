/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.cast

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.cert.CertificateException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Every way a connection can fail must close the socket: a half-open connection blocks the Chromecast's small accept
 * queue for everyone (seen on the real device, 18.11.2026). A deliberate close says CLOSE first.
 */
class CastClientCloseTest {
    private class FakeSocket(private val failHandshake: Boolean = false) : SSLSocket() {
        val toClient = PipedOutputStream()
        private val input = PipedInputStream(toClient, 64 * 1024)
        val written = ByteArrayOutputStream()
        @Volatile var closedCount = 0

        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) = synchronized(written) { if (closedCount > 0) throw IOException("closed"); written.write(b) }
        }
        override fun close() {
            closedCount++
            runCatching { toClient.close() }
            runCatching { input.close() }
        }
        override fun isClosed() = closedCount > 0
        override fun setSoTimeout(timeout: Int) {}
        override fun startHandshake() { if (failHandshake) throw IOException("handshake failed") }

        /** What the device sends: one framed CastMessage. */
        fun reply(message: CastMessage) = CastMessage.write(toClient, message)

        fun sentMessages(): List<CastMessage> {
            val input = DataInputStream(ByteArrayInputStream(synchronized(written) { written.toByteArray() }))
            val messages = mutableListOf<CastMessage>()
            while (true) {
                try { messages += CastMessage.read(input) } catch (_: EOFException) { return messages }
            }
        }

        override fun getSupportedCipherSuites() = emptyArray<String>()
        override fun getEnabledCipherSuites() = emptyArray<String>()
        override fun setEnabledCipherSuites(suites: Array<out String>?) {}
        override fun getSupportedProtocols() = emptyArray<String>()
        override fun getEnabledProtocols() = emptyArray<String>()
        override fun setEnabledProtocols(protocols: Array<out String>?) {}
        override fun getSession(): SSLSession = throw UnsupportedOperationException()
        override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) {}
        override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) {}
        override fun setUseClientMode(mode: Boolean) {}
        override fun getUseClientMode() = true
        override fun setNeedClientAuth(need: Boolean) {}
        override fun getNeedClientAuth() = false
        override fun setWantClientAuth(want: Boolean) {}
        override fun getWantClientAuth() = false
        override fun setEnableSessionCreation(flag: Boolean) {}
        override fun getEnableSessionCreation() = true
    }

    private val authReply = CastMessage("receiver-0", "sender-0", CastDeviceAuth.NAMESPACE, "", byteArrayOf(1, 2, 3))
    private val closedEvents = CountDownLatch(1)
    private val listener = object : CastClient.Listener {
        override fun onClosed(error: Throwable?) = closedEvents.countDown()
    }

    private fun client(
        socket: FakeSocket,
        authenticate: (ByteArray, ByteArray, SSLSocket) -> CastDeviceAuth.Device = { _, _, _ ->
            CastDeviceAuth.Device(CastRoots.all.first())
        }
    ) = CastClient("tv", 8009, listener, openSocket = { _, _, _ -> socket }, authenticate = authenticate)

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("expected the connection to fail")
        } catch (_: Exception) {
        }
    }

    @Test
    fun failedTcpConnectLeavesNothingOpen() {
        val client = CastClient("tv", 8009, listener, openSocket = { _, _, _ -> throw IOException("timed out") })
        expectFailure { client.connect(200) }
        assertTrue(closedEvents.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun failedHandshakeClosesTheSocket() {
        val socket = FakeSocket(failHandshake = true)
        expectFailure { client(socket).connect(200) }
        assertEquals(1, socket.closedCount)
    }

    @Test
    fun missingAuthReplyClosesTheSocket() {
        val socket = FakeSocket()
        expectFailure { client(socket).connect(300) }
        assertTrue(socket.closedCount >= 1)
        // Nothing but the challenge went out: no CONNECT for a device that never proved itself.
        assertEquals(listOf(CastDeviceAuth.NAMESPACE), socket.sentMessages().map { it.namespace })
    }

    @Test
    fun rejectedDeviceClosesTheSocket() {
        val socket = FakeSocket()
        socket.reply(authReply)
        expectFailure { client(socket) { _, _, _ -> throw CertificateException("not a Cast device") }.connect(2_000) }
        assertTrue(socket.closedCount >= 1)
        assertFalse(socket.sentMessages().any { it.namespace == CastProtocol.NS_CONNECTION })
    }

    @Test
    fun deviceHangingUpDuringAuthClosesTheSocket() {
        val socket = FakeSocket()
        socket.toClient.close()
        expectFailure { client(socket).connect(2_000) }
        assertTrue(socket.closedCount >= 1)
    }

    @Test
    fun closeSaysGoodbyeBeforeClosing() {
        val socket = FakeSocket()
        socket.reply(authReply)
        val client = client(socket)
        client.connect(2_000)
        client.close()
        val sent = socket.sentMessages()
        assertEquals("CONNECT", CastProtocol.typeOf(sent.first { it.namespace == CastProtocol.NS_CONNECTION }.payload))
        val last = sent.last()
        assertEquals(CastProtocol.NS_CONNECTION, last.namespace)
        assertEquals(CastProtocol.RECEIVER_ID, last.destinationId)
        assertEquals("CLOSE", CastProtocol.typeOf(last.payload))
        assertEquals(1, socket.closedCount)
        client.close()
        assertEquals("closing twice closes once", 1, socket.closedCount)
    }

    @Test
    fun deviceHangingUpLaterClosesTheSocket() {
        val socket = FakeSocket()
        socket.reply(authReply)
        client(socket).connect(2_000)
        socket.toClient.close()
        assertTrue(closedEvents.await(2, TimeUnit.SECONDS))
        assertEquals(1, socket.closedCount)
    }
}
