/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.cast

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastMessageTest {
    @Test
    fun encodesTheProtobufFieldByField() {
        val bytes = CastMessage("s", "r", "n", "{}").encode()
        // 08 00 | 12 01 's' | 1a 01 'r' | 22 01 'n' | 28 00 | 32 02 '{' '}'
        val expected = byteArrayOf(
            0x08, 0x00, 0x12, 0x01, 's'.code.toByte(), 0x1a, 0x01, 'r'.code.toByte(), 0x22, 0x01, 'n'.code.toByte(),
            0x28, 0x00, 0x32, 0x02, '{'.code.toByte(), '}'.code.toByte()
        )
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun roundTripsTextAndLongPayloads() {
        val payload = "{\"type\":\"LOAD\",\"title\":\"Ünïcödé ♪\",\"pad\":\"" + "x".repeat(300) + "\"}"
        val decoded = CastMessage.decode(
            CastMessage(CastProtocol.SENDER_ID, "web-5", CastProtocol.NS_MEDIA, payload).encode()
        )
        assertEquals(CastProtocol.SENDER_ID, decoded.sourceId)
        assertEquals("web-5", decoded.destinationId)
        assertEquals(CastProtocol.NS_MEDIA, decoded.namespace)
        assertEquals(payload, decoded.payload)
        assertNull(decoded.binary)
    }

    @Test
    fun roundTripsBinaryPayloads() {
        val binary = ByteArray(200) { it.toByte() }
        val bytes = CastMessage("a", "b", "c", "", binary).encode()
        // payload_type BINARY = 1 comes before payload_binary (field 7).
        assertEquals(0x28.toByte(), bytes[11])
        assertEquals(1.toByte(), bytes[12])
        assertEquals(0x3a.toByte(), bytes[13])
        assertArrayEquals(binary, CastMessage.decode(bytes).binary)
    }

    @Test
    fun skipsUnknownFields() {
        val known = CastMessage("a", "b", "c", "d").encode()
        // Field 9 as varint and field 10 as fixed32 in front of the known fields.
        val withUnknown = byteArrayOf(0x48, 0x96.toByte(), 0x01, 0x55, 1, 2, 3, 4) + known
        assertEquals("d", CastMessage.decode(withUnknown).payload)
    }

    @Test(expected = IOException::class)
    fun rejectsTruncatedFields() {
        val bytes = CastMessage("a", "b", "c", "payload").encode()
        CastMessage.decode(bytes.copyOf(bytes.size - 3))
    }

    @Test
    fun framesWithABigEndianLength() {
        val out = ByteArrayOutputStream()
        val message = CastMessage("a", "b", "c", "x".repeat(70_000 / 2))
        CastMessage.write(out, message)
        val framed = out.toByteArray()
        val size = message.encode().size
        assertEquals(size, ((framed[0].toInt() and 0xff) shl 24) or ((framed[1].toInt() and 0xff) shl 16) or
            ((framed[2].toInt() and 0xff) shl 8) or (framed[3].toInt() and 0xff))
        assertEquals(message.payload, CastMessage.read(DataInputStream(ByteArrayInputStream(framed))).payload)
    }

    @Test(expected = IOException::class)
    fun refusesOversizedFrames() {
        val header = byteArrayOf(0, 0x10, 0, 0) // 1 MiB
        CastMessage.read(DataInputStream(ByteArrayInputStream(header + ByteArray(16))))
    }
}
