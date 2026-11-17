/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.cast

/** A cast device found on the network; [id] (TXT `id`) keys its pinned device certificate. */
data class CastDevice(
    val id: String,
    val name: String,
    val model: String?,
    val host: String,
    val port: Int,
    /** Speakers and groups have no video output; they get a speaker icon instead of a TV. */
    val hasVideo: Boolean
) {
    companion object {
        /**
         * Builds the device from its mDNS record: TXT `fn` (name), `md` (model), `id` (UUID) and `ca` (capability bits,
         * bit 0 = video out), as read by pychromecast's discovery.py (MIT).
         */
        fun fromTxt(serviceName: String, txt: Map<String, ByteArray?>, host: String, port: Int): CastDevice {
            fun text(key: String) = txt[key]?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }
            val capabilities = text("ca")?.toIntOrNull()
            return CastDevice(
                id = text("id") ?: serviceName,
                name = text("fn") ?: serviceName,
                model = text("md"),
                host = host,
                port = port,
                hasVideo = capabilities == null || capabilities and 1 != 0
            )
        }
    }
}
