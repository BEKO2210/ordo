/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.cast

import java.io.File
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Plays a file on a real Chromecast with the app's own sender and file server. Skipped unless CAST_HOST and
 * CAST_TEST_FILE are set, so normal builds and CI never touch the network:
 *
 *     CAST_HOST=192.168.2.37 CAST_TEST_FILE=/tmp/tone.mp3 ./gradlew :app:testDebugUnitTest \
 *         --tests '*CastRealDeviceCheck*' -i
 *
 * The TV volume is set to 10 %, and the receiver app is closed again at the end.
 */
class CastRealDeviceCheck {
    private val statuses = LinkedBlockingQueue<CastProtocol.MediaStatus>()
    private val started = System.currentTimeMillis()

    private fun log(text: String) = println("[cast %5.1fs] %s".format((System.currentTimeMillis() - started) / 1000.0, text))

    private val listener = object : CastClient.Listener {
        override fun onMediaStatus(status: CastProtocol.MediaStatus) {
            log("MEDIA_STATUS ${status.playerState} ${status.idleReason ?: ""} t=${status.currentTimeSeconds}s " +
                "duration=${status.durationSeconds}s session=${status.mediaSessionId} request=${status.requestId}")
            statuses += status
        }

        override fun onReceiverStatus(status: CastProtocol.ReceiverStatus) =
            log("RECEIVER_STATUS transport=${status.transportId} volume=${status.volumeLevel} muted=${status.muted}")

        override fun onRequestFailed(type: String, requestId: Int) = log("FAILED $type request=$requestId")

        override fun onClosed(error: Throwable?) = log("closed ${error ?: "by us"}")
    }

    private fun awaitState(state: String, timeoutS: Long = 20, check: (CastProtocol.MediaStatus) -> Boolean = { true }):
        CastProtocol.MediaStatus {
        val deadline = System.currentTimeMillis() + timeoutS * 1000
        while (true) {
            val left = deadline - System.currentTimeMillis()
            val status = statuses.poll(left.coerceAtLeast(1), TimeUnit.MILLISECONDS)
                ?: throw AssertionError("no $state within ${timeoutS}s")
            if (status.playerState == state && check(status)) return status
        }
    }

    @Test
    fun playsPausesSeeksAndStopsOnARealChromecast() {
        val host = System.getenv("CAST_HOST")
        val file = System.getenv("CAST_TEST_FILE")?.let(::File)
        assumeTrue("CAST_HOST / CAST_TEST_FILE not set", host != null && file != null && file.isFile)
        file!!

        // Device auth: the device certificate chains to a Cast root and signed this connection's TLS certificate.
        val first = CastClient(host!!, 8009, listener)
        val device = first.connect()
        log("device authenticated: ${device.certificate.subjectX500Principal}, issued by " +
            "${device.certificate.issuerX500Principal}, pin ${device.pin}")
        first.close()

        // Second connection: a new TLS session, the same device certificate.
        // CAST_APP_ID launches another receiver app, e.g. Ordo's own (59928C44); default: Default Media Receiver.
        val appId = System.getenv("CAST_APP_ID")?.takeIf { it.isNotEmpty() } ?: CastProtocol.DEFAULT_MEDIA_RECEIVER
        val client = CastClient(host, 8009, listener, listOf(appId))
        assertEquals(device.pin, client.connect().pin)
        log("second connection authenticated with the same pin")
        val receiver = client.launch(appId)
        log("receiver $appId running, transport ${receiver.transportId}")
        client.setVolume(level = 0.1)

        val server = CastFileServer(client.localAddress, InetAddress.getByName(host))
        try {
            val source = CastFileServer.Source(file.length(), "audio/mpeg") { offset ->
                file.inputStream().also { it.skip(offset) }
            }
            val (url, _) = server.serve(source, null)
            log("serving ${file.name} (${file.length()} bytes) at $url")
            client.load(CastProtocol.Track(url, "audio/mpeg", "Ordo cast check", "Ordo", "Tests", null), 0.0, true)
            awaitState("PLAYING")
            log("PLAYING confirmed")
            Thread.sleep(3_000)
            client.pause()
            awaitState("PAUSED")
            log("PAUSED confirmed")
            client.seek(10.0)
            client.requestMediaStatus()
            val seeked = awaitState("PAUSED") { it.currentTimeSeconds in 9.5..11.0 }
            log("SEEK confirmed at ${seeked.currentTimeSeconds}s")
            client.play()
            awaitState("PLAYING")
            Thread.sleep(2_000)
            client.requestMediaStatus()
            val later = awaitState("PLAYING") { it.currentTimeSeconds > 10.5 }
            log("playing on from the seek: ${later.currentTimeSeconds}s")
            client.stopMedia()
            awaitState("IDLE")
            log("STOP confirmed")
        } finally {
            client.stopApp()
            client.flush()
            Thread.sleep(1_000)
            client.close()
            server.close()
        }
    }
}
