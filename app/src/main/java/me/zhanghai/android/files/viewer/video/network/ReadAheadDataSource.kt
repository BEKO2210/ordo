/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.network

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads [chunkBytes] at a time from [upstream] and hands them out in the small pieces Media3's extractors ask for:
 * one SMB/SFTP/WebDAV round trip per chunk instead of one per sample. Counts every byte for the speed display.
 */
@UnstableApi
class ReadAheadDataSource(
    private val upstream: DataSource,
    private val chunkBytes: Int,
    private val counter: AtomicLong
) : DataSource {
    private val buffer = ByteArray(chunkBytes)
    private var start = 0
    private var end = 0
    private var ended = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        start = 0
        end = 0
        ended = false
        return upstream.open(dataSpec)
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (start == end) {
            if (ended) return C.RESULT_END_OF_INPUT
            fill()
            if (start == end) return C.RESULT_END_OF_INPUT
        }
        val count = minOf(length, end - start)
        System.arraycopy(buffer, start, target, offset, count)
        start += count
        return count
    }

    /** Fills the chunk; a short read from the server is fine, only the end of input stops early. */
    private fun fill() {
        start = 0
        end = 0
        while (end < chunkBytes) {
            val read = upstream.read(buffer, end, chunkBytes - end)
            if (read == C.RESULT_END_OF_INPUT) {
                ended = true
                break
            }
            end += read
            counter.addAndGet(read.toLong())
            // Enough to go on with: hand it out instead of waiting for the full chunk on a slow line.
            if (end >= chunkBytes / 4) break
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        start = 0
        end = 0
        upstream.close()
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val chunkBytes: Int,
        val bytesRead: AtomicLong = AtomicLong()
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ReadAheadDataSource(upstream.createDataSource(), chunkBytes, bytesRead)
    }
}
