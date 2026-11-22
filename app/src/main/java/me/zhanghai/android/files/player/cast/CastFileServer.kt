/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.cast

import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Hands exactly one file (the current track, plus its cover) to the cast device over HTTP. It listens only on the
 * address the device reached us on, answers only [allowedClient], and every file gets a fresh random 128-bit path;
 * anything else is 404. No listing, no other files, and it runs only while a cast session does.
 */
class CastFileServer(bindAddress: InetAddress, private val allowedClient: InetAddress?) : Closeable {
    /** One servable file: its size, its type and a way to read it from an offset. */
    class Source(val size: Long, val contentType: String, val open: (offset: Long) -> InputStream)

    private val serverSocket = ServerSocket().apply { bind(InetSocketAddress(bindAddress, 0)) }
    private val workers = Executors.newCachedThreadPool { Thread(it, "cast-http").apply { isDaemon = true } }
    private val random = SecureRandom()
    @Volatile private var files: Map<String, Source> = emptyMap()
    private val acceptStopped = CountDownLatch(1)

    val port: Int get() = serverSocket.localPort
    private val host = hostForUrl(bindAddress.hostAddress)

    init {
        workers.execute(::acceptLoop)
    }

    /** Replaces whatever was served before; returns the track's URL and, when there is one, the cover's. */
    fun serve(track: Source, cover: Source?): Pair<String, String?> {
        val trackPath = "/" + token()
        val coverPath = cover?.let { "/" + token() }
        files = buildMap {
            put(trackPath, track)
            if (coverPath != null) put(coverPath, cover)
        }
        return url(trackPath) to coverPath?.let(::url)
    }

    /** Like [serve] for any number of files (a film and its subtitles); returns their URLs in the same order. */
    fun serveAll(sources: List<Source>): List<String> {
        val paths = sources.map { "/" + token() }
        files = paths.zip(sources).toMap()
        return paths.map(::url)
    }

    /**
     * Stops listening before it returns. A thread blocked in accept() keeps the listening socket alive in the kernel
     * until it wakes up (the JDK only pre-closes and signals it), so a connection could still slip in right after
     * ServerSocket.close() (CI, 20.11.2026); waiting for the accept loop to end closes that gap. Open transfers end in
     * the background (shutdownNow).
     */
    override fun close() {
        files = emptyMap()
        runCatching { serverSocket.close() }
        workers.shutdownNow()
        acceptStopped.await(ACCEPT_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun url(path: String) = "http://$host:$port$path"

    private fun token(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun acceptLoop() {
        try {
            while (!serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: SocketException) {
                    return
                }
                if (allowedClient != null && socket.inetAddress != allowedClient) {
                    runCatching { socket.close() }
                    continue
                }
                runCatching { workers.execute { socket.use(::handle) } }.onFailure { runCatching { socket.close() } }
            }
        } finally {
            acceptStopped.countDown()
        }
    }

    private fun handle(socket: Socket) {
        runCatching {
            socket.soTimeout = 30_000
            val input = socket.getInputStream().buffered()
            val requestLine = readLine(input) ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0)
            val source = parts.getOrNull(1)?.let { files[it] }
            val out = socket.getOutputStream().buffered()
            when {
                method != "GET" && method != "HEAD" -> respond(out, "405 Method Not Allowed", emptyMap())
                source == null -> respond(out, "404 Not Found", emptyMap())
                else -> {
                    val range = headers["range"]?.let { parseRange(it, source.size) }
                    if (range != null && range.isEmpty()) {
                        respond(out, "416 Range Not Satisfiable", mapOf("Content-Range" to "bytes */${source.size}"))
                        out.flush()
                        return
                    }
                    val start = range?.first ?: 0L
                    val end = range?.last ?: (source.size - 1)
                    val length = (end - start + 1).coerceAtLeast(0L)
                    val responseHeaders = buildMap {
                        put("Content-Type", source.contentType)
                        put("Content-Length", length.toString())
                        if (range != null) put("Content-Range", "bytes $start-$end/${source.size}")
                    }
                    respond(out, if (range != null) "206 Partial Content" else "200 OK", responseHeaders)
                    if (method == "GET" && length > 0) source.open(start).use { copy(it, out, length) }
                }
            }
            out.flush()
        }
    }

    private fun respond(out: java.io.OutputStream, status: String, headers: Map<String, String>) {
        val text = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            // The Default Media Receiver is a web page; CORS keeps it allowed to read the file.
            append("Access-Control-Allow-Origin: *\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
            if ("Content-Length" !in headers) append("Content-Length: 0\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        out.write(text.toByteArray(Charsets.ISO_8859_1))
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, length: Long) {
        val buffer = ByteArray(64 * 1024)
        var left = length
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            left -= read
        }
    }

    /** Request headers are short; a line longer than this is not a real client. */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return null
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length > 8 * 1024) return null
            line.append(byte.toChar())
        }
    }

    companion object {
        private const val ACCEPT_STOP_TIMEOUT_MS = 2_000L
        internal val INVALID = LongRange.EMPTY

        internal fun hostForUrl(hostAddress: String): String {
            val withoutZone = hostAddress.substringBefore('%')
            return if (':' in withoutZone) "[$withoutZone]" else withoutZone
        }

        /**
         * A single `bytes=` range as an inclusive [LongRange] clipped to the file; null when there is no usable
         * range header (serve everything), [INVALID] when it cannot be satisfied (416). Multiple ranges are not offered.
         */
        fun parseRange(header: String, size: Long): LongRange? {
            val spec = header.trim().takeIf { it.startsWith("bytes=", ignoreCase = true) }?.substring(6)?.trim()
                ?: return null
            if (',' in spec) return null
            val dash = spec.indexOf('-').takeIf { it >= 0 } ?: return null
            val first = spec.substring(0, dash).trim()
            val last = spec.substring(dash + 1).trim()
            return if (first.isEmpty()) {
                val suffix = last.toLongOrNull() ?: return null
                if (suffix <= 0 || size == 0L) INVALID else (size - suffix).coerceAtLeast(0L)..(size - 1)
            } else {
                val start = first.toLongOrNull() ?: return null
                val end = if (last.isEmpty()) size - 1 else last.toLongOrNull()?.coerceAtMost(size - 1) ?: return null
                if (start >= size || end < start) INVALID else start..end
            }
        }
    }
}
