/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.network

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAheadDataSourceTest {
    /** A server that hands out at most [perRead] bytes a call and counts the calls (round trips). */
    private class FakeServer(private val data: ByteArray, private val perRead: Int) : DataSource {
        var position = 0
        var calls = 0
        override fun addTransferListener(transferListener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long = data.size.toLong()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            calls++
            if (position == data.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, perRead, data.size - position)
            System.arraycopy(data, position, buffer, offset, count)
            position += count
            return count
        }
        override fun getUri(): Uri? = null
        override fun close() {}
    }

    private fun readAll(source: DataSource, piece: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(piece)
        while (true) {
            val read = source.read(buffer, 0, piece)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    @Test
    fun smallReadsBecomeFewServerRoundTrips() {
        val data = ByteArray(1_000_000) { (it * 31).toByte() }
        val server = FakeServer(data, perRead = 64 * 1024)
        val counter = AtomicLong()
        val source = ReadAheadDataSource(server, 512 * 1024, counter)
        assertArrayEquals(data, readAll(source, 1000))
        // A thousand small reads by the extractor, but only one server call per 64 KiB (+ the one that sees the end).
        assertEquals(data.size / (64 * 1024) + 2, server.calls)
        assertEquals(data.size.toLong(), counter.get())
    }

    @Test
    fun shortServerReadsAndOddPiecesKeepEveryByte() {
        val data = ByteArray(10_007) { it.toByte() }
        val source = ReadAheadDataSource(FakeServer(data, perRead = 333), 4096, AtomicLong())
        assertArrayEquals(data, readAll(source, 77))
    }

    @Test
    fun emptyFileEndsAtOnce() {
        val source = ReadAheadDataSource(FakeServer(ByteArray(0), 10), 4096, AtomicLong())
        assertEquals(0, source.read(ByteArray(10), 0, 0))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(10), 0, 10))
    }
}
