/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.file

import java.io.File
import java.io.IOException

/**
 * ".ts" is TypeScript by name (as in Material Files) but also MPEG transport streams (DVB recordings). The owner's rule:
 * video wins when the content is video. A stream has the sync byte 0x47 at the start of every 188-byte packet.
 */
object MpegTsSniffer {
    const val MIME_TYPE = "video/mp2t"
    private const val PACKET = 188
    private const val PACKETS = 3
    private const val SYNC = 0x47.toByte()

    /** Three packet starts in a row carry the sync byte. */
    fun looksLikeMpegTs(head: ByteArray, length: Int = head.size): Boolean =
        length > (PACKETS - 1) * PACKET && (0 until PACKETS).all { head[it * PACKET] == SYNC }

    fun isMpegTs(path: String): Boolean = try {
        val head = ByteArray((PACKETS - 1) * PACKET + 1)
        val read = File(path).inputStream().use { input ->
            var total = 0
            while (total < head.size) {
                val n = input.read(head, total, head.size - total)
                if (n < 0) break
                total += n
            }
            total
        }
        looksLikeMpegTs(head, read)
    } catch (e: IOException) {
        false
    }
}
