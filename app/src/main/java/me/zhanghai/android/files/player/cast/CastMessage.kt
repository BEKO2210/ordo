/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.cast

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream

/**
 * One Cast V2 `CastMessage`, framed as 4 bytes big-endian length plus the protobuf.
 * Fields from Chromium's cast_channel.proto (BSD): 1 protocol_version, 2 source_id, 3 destination_id, 4 namespace,
 * 5 payload_type (0 STRING, 1 BINARY), 6 payload_utf8, 7 payload_binary. Only device auth uses [binary].
 */
class CastMessage(
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payload: String,
    val binary: ByteArray? = null
) {
    fun encode(): ByteArray = Proto.Writer().apply {
        // proto2 required fields are written even at their default value 0 (CASTV2_1_0).
        varint(1, 0L)
        string(2, sourceId)
        string(3, destinationId)
        string(4, namespace)
        if (binary != null) {
            varint(5, 1L)
            bytes(7, binary)
        } else {
            varint(5, 0L)
            string(6, payload)
        }
    }.toByteArray()

    override fun toString() = "CastMessage($sourceId -> $destinationId, $namespace, $payload)"

    companion object {
        /** Real messages are a few KiB; the cap keeps a broken or hostile peer from making us allocate gigabytes. */
        const val MAX_SIZE = 64 * 1024

        fun decode(bytes: ByteArray): CastMessage {
            val fields = Proto.read(bytes)
            fun text(field: Int) = (fields[field]?.firstOrNull() as? ByteArray)?.toString(Charsets.UTF_8).orEmpty()
            return CastMessage(text(2), text(3), text(4), text(6), fields[7]?.firstOrNull() as? ByteArray)
        }

        fun write(out: OutputStream, message: CastMessage) {
            val body = message.encode()
            val size = body.size
            out.write(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
            out.write(body)
            out.flush()
        }

        fun read(input: DataInputStream): CastMessage {
            val size = input.readInt()
            if (size < 0 || size > MAX_SIZE) throw IOException("Cast message of $size bytes")
            val body = ByteArray(size)
            input.readFully(body)
            return decode(body)
        }
    }
}

/** The few protobuf wire rules Cast needs: varints and length-delimited fields. */
internal object Proto {
    class Writer {
        private val out = ByteArrayOutputStream()

        fun varint(field: Int, value: Long) {
            raw((field shl 3).toLong())
            raw(value)
        }

        fun bytes(field: Int, value: ByteArray) {
            raw((field shl 3 or 2).toLong())
            raw(value.size.toLong())
            out.write(value)
        }

        fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun raw(value: Long) {
            var rest = value
            while (rest and 0x7FL.inv() != 0L) {
                out.write(((rest and 0x7F) or 0x80).toInt())
                rest = rest ushr 7
            }
            out.write(rest.toInt())
        }
    }

    /** Field number to its values in order: `Long` for varints, `ByteArray` for length-delimited fields. */
    fun read(bytes: ByteArray): Map<Int, List<Any>> {
        val fields = mutableMapOf<Int, MutableList<Any>>()
        var position = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (position >= bytes.size || shift > 63) throw IOException("Truncated varint")
                val byte = bytes[position++].toInt()
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
        }
        while (position < bytes.size) {
            val key = varint()
            val field = (key ushr 3).toInt()
            val value: Any = when ((key and 7).toInt()) {
                0 -> varint()
                2 -> {
                    val length = varint()
                    if (length < 0 || length > bytes.size - position) throw IOException("Bad field length")
                    bytes.copyOfRange(position, position + length.toInt()).also { position += it.size }
                }
                5 -> { position += 4; continue }
                1 -> { position += 8; continue }
                else -> throw IOException("Unsupported wire type in key $key")
            }
            if (position > bytes.size) throw IOException("Truncated field")
            fields.getOrPut(field) { mutableListOf() } += value
        }
        return fields
    }
}
