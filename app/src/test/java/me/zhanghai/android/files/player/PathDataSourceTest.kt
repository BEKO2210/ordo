/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.nio.charset.StandardCharsets
import java8.nio.file.Paths
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File

@UnstableApi
class PathDataSourceTest {
    private lateinit var file: File
    private lateinit var source: PathDataSource

    @Before
    fun setUp() {
        file = File.createTempFile("ordo-audio", ".bin")
        file.writeBytes(CONTENT)
        source = PathDataSource()
    }

    @After
    fun tearDown() {
        source.close()
        file.delete()
    }

    @Test
    fun readsWholeFileAndReportsLength() {
        assertEquals(CONTENT.size.toLong(), source.open(Paths.get(file.path)))
        assertArrayEquals(CONTENT, readAll())
    }

    @Test
    fun readsFromOffset() {
        assertEquals(5L, source.open(Paths.get(file.path), 6L, C.LENGTH_UNSET.toLong()))
        assertArrayEquals("world".toByteArray(StandardCharsets.UTF_8), readAll())
    }

    @Test
    fun requestedRangePastEndStopsAtEnd() {
        assertEquals(3L, source.open(Paths.get(file.path), 8L, 20L))
        assertArrayEquals("rld".toByteArray(StandardCharsets.UTF_8), readAll())
    }

    @Test
    fun positionAtEndIsAnEmptySuccessfulRead() {
        assertEquals(0L, source.open(Paths.get(file.path), CONTENT.size.toLong(), 4L))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(4), 0, 4))
    }

    @Test
    fun positionPastEndIsRejected() {
        assertThrows(androidx.media3.datasource.DataSourceException::class.java) {
            source.open(Paths.get(file.path), CONTENT.size + 1L, 1L)
        }
    }

    private fun readAll(): ByteArray {
        val result = ArrayList<Byte>()
        val buffer = ByteArray(3)
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            repeat(read) { result += buffer[it] }
        }
        return result.toByteArray()
    }

    companion object {
        private val CONTENT = "hello world".toByteArray(StandardCharsets.UTF_8)
    }
}
