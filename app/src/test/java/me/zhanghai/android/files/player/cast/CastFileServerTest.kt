/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.cast

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CastFileServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val data = ByteArray(10_000) { (it * 7).toByte() }
    private val source = CastFileServer.Source(data.size.toLong(), "audio/mpeg") { offset ->
        ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
    }
    private val server = CastFileServer(loopback, loopback)

    @After
    fun close() = server.close()

    private class Response(val status: String, val headers: Map<String, String>, val body: ByteArray)

    private fun request(url: String, method: String = "GET", range: String? = null): Response {
        val uri = URI(url)
        Socket(uri.host, uri.port).use { socket ->
            val request = buildString {
                append("$method ${uri.rawPath} HTTP/1.1\r\nHost: ${uri.host}\r\n")
                if (range != null) append("Range: $range\r\n")
                append("\r\n")
            }
            socket.getOutputStream().write(request.toByteArray())
            val bytes = socket.getInputStream().readBytes()
            val split = (0 until bytes.size - 3).first {
                bytes[it] == '\r'.code.toByte() && bytes[it + 1] == '\n'.code.toByte() &&
                    bytes[it + 2] == '\r'.code.toByte() && bytes[it + 3] == '\n'.code.toByte()
            }
            val lines = String(bytes, 0, split, Charsets.ISO_8859_1).split("\r\n")
            val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            return Response(lines[0].substringAfter(' '), headers, bytes.copyOfRange(split + 4, bytes.size))
        }
    }

    @Test
    fun servesTheWholeFile() {
        val (url, cover) = server.serve(source, null)
        assertNull(cover)
        val response = request(url)
        assertEquals("200 OK", response.status)
        assertEquals("audio/mpeg", response.headers["content-type"])
        assertEquals("10000", response.headers["content-length"])
        assertEquals("bytes", response.headers["accept-ranges"])
        assertEquals("*", response.headers["access-control-allow-origin"])
        assertArrayEquals(data, response.body)
    }

    @Test
    fun servesRanges() {
        val (url, _) = server.serve(source, null)
        val middle = request(url, range = "bytes=100-199")
        assertEquals("206 Partial Content", middle.status)
        assertEquals("bytes 100-199/10000", middle.headers["content-range"])
        assertEquals("100", middle.headers["content-length"])
        assertArrayEquals(data.copyOfRange(100, 200), middle.body)
        val open = request(url, range = "bytes=9990-")
        assertArrayEquals(data.copyOfRange(9990, 10_000), open.body)
        val suffix = request(url, range = "bytes=-5")
        assertEquals("bytes 9995-9999/10000", suffix.headers["content-range"])
        assertArrayEquals(data.copyOfRange(9995, 10_000), suffix.body)
        val beyond = request(url, range = "bytes=20000-")
        assertEquals("416 Range Not Satisfiable", beyond.status)
        assertEquals("bytes */10000", beyond.headers["content-range"])
    }

    @Test
    fun headHasNoBody() {
        val (url, _) = server.serve(source, null)
        val response = request(url, method = "HEAD")
        assertEquals("200 OK", response.status)
        assertEquals("10000", response.headers["content-length"])
        assertEquals(0, response.body.size)
        assertEquals("405 Method Not Allowed", request(url, method = "POST").status)
    }

    @Test
    fun onlyTheCurrentTokenWorks() {
        val (first, _) = server.serve(source, null)
        val path = URI(first).path
        assertTrue("token has 128 bits", path.length == 33)
        assertEquals("404 Not Found", request(first.replace(path, "/" + "0".repeat(32))).status)
        assertEquals("404 Not Found", request(first.replace(path, "/")).status)
        val (second, coverUrl) = server.serve(source, source)
        assertNotEquals(first, second)
        assertEquals("404 Not Found", request(first).status)
        assertEquals("200 OK", request(second).status)
        assertEquals("200 OK", request(coverUrl!!).status)
    }

    @Test
    fun refusesOtherClients() {
        val other = CastFileServer(loopback, InetAddress.getByName("192.0.2.1"))
        try {
            val (url, _) = other.serve(source, null)
            val uri = URI(url)
            Socket(uri.host, uri.port).use { socket ->
                // The server hangs up without a word: end of stream, or a reset when our request crossed it.
                val read = try {
                    socket.getOutputStream().write("GET ${uri.path} HTTP/1.1\r\n\r\n".toByteArray())
                    socket.getInputStream().read()
                } catch (_: IOException) {
                    -1
                }
                assertEquals(-1, read)
            }
        } finally {
            other.close()
        }
    }

    @Test
    fun stopsListeningWhenClosed() {
        val (url, _) = server.serve(source, null)
        server.close()
        val uri = URI(url)
        try {
            Socket(uri.host, uri.port).close()
            fail("server still accepts connections")
        } catch (_: IOException) {
        }
    }

    @Test
    fun parsesRangeHeaders() {
        assertEquals(0L..99L, CastFileServer.parseRange("bytes=0-99", 1000))
        assertEquals(990L..999L, CastFileServer.parseRange("bytes=990-5000", 1000))
        assertEquals(900L..999L, CastFileServer.parseRange("bytes=-100", 1000))
        assertEquals(0L..999L, CastFileServer.parseRange("bytes=-5000", 1000))
        assertTrue(CastFileServer.parseRange("bytes=1000-", 1000)!!.isEmpty())
        assertTrue(CastFileServer.parseRange("bytes=50-10", 1000)!!.isEmpty())
        assertNull(CastFileServer.parseRange("bytes=0-1,5-6", 1000))
        assertNull(CastFileServer.parseRange("items=0-1", 1000))
        assertNull(CastFileServer.parseRange("bytes=a-b", 1000))
    }
}
