/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.file

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MpegTsSnifferTest {
    private fun stream(packets: Int) = ByteArray(packets * 188).also { b -> for (i in 0 until packets) b[i * 188] = 0x47 }

    @Test
    fun syncBytesEvery188IsAStream() {
        assertTrue(MpegTsSniffer.looksLikeMpegTs(stream(3)))
    }

    @Test
    fun typeScriptIsNot() {
        assertFalse(MpegTsSniffer.looksLikeMpegTs("export const x: number = 1;\n".repeat(30).toByteArray()))
        // Starts with "G" but the next packets don't sync.
        assertFalse(MpegTsSniffer.looksLikeMpegTs(("G" + "a".repeat(600)).toByteArray()))
    }

    @Test
    fun tooShortIsNot() {
        assertFalse(MpegTsSniffer.looksLikeMpegTs(stream(3), 300))
        assertFalse(MpegTsSniffer.looksLikeMpegTs(ByteArray(0)))
    }

    @Test
    fun readsTheFileHead() {
        val ts = File.createTempFile("rec", ".ts").apply { writeBytes(stream(10)); deleteOnExit() }
        val code = File.createTempFile("app", ".ts").apply { writeText("let a = 1\n".repeat(100)); deleteOnExit() }
        assertTrue(MpegTsSniffer.isMpegTs(ts.path))
        assertFalse(MpegTsSniffer.isMpegTs(code.path))
        assertFalse(MpegTsSniffer.isMpegTs("/nonexistent/x.ts"))
    }
}
