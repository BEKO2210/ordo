/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.network

import java.util.Locale
import kotlin.math.min

object VideoBuffering {
    val NETWORK_SCHEMES = setOf("sftp", "smb", "ftp", "ftps", "ftpes", "dav", "davs")

    fun isNetwork(uri: String?): Boolean {
        if (uri.isNullOrEmpty()) return false
        val colon = uri.indexOf(':')
        return colon > 0 && uri.substring(0, colon).lowercase(Locale.ROOT) in NETWORK_SCHEMES
    }

    data class Config(
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val playbackMs: Int,
        val rebufferMs: Int,
        val backBufferMs: Int,
        val retries: Int,
        val readAheadBytes: Int
    )

    val LOCAL = Config(50_000, 50_000, 1_000, 2_000, 0, 3, 0)
    val NETWORK = Config(60_000, 180_000, 2_500, 5_000, 30_000, 12, 512 * 1024)

    fun forUri(uri: String?): Config = if (isNetwork(uri)) NETWORK else LOCAL

    fun retryDelayMs(errorCount: Int): Long = if (errorCount < 1) 1_000 else min(errorCount * 1_000L, 5_000L)

    fun bufferPercent(bufferedAheadMs: Long, config: Config): Int = when {
        bufferedAheadMs <= 0 -> 0
        bufferedAheadMs >= config.rebufferMs -> 99
        else -> (bufferedAheadMs * 100 / config.rebufferMs).toInt().coerceIn(0, 99)
    }

    fun speedText(bytesPerSecond: Long, locale: Locale): String = when {
        bytesPerSecond >= 1_000_000 -> String.format(locale, "%.1f MB/s", bytesPerSecond / 1_000_000.0)
        bytesPerSecond >= 1_000 -> "${bytesPerSecond / 1_000} kB/s"
        else -> "$bytesPerSecond B/s"
    }
}
