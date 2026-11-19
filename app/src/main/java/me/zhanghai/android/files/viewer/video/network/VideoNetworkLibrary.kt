/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.network

import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.guessFromPath
import me.zhanghai.android.files.file.isVideo
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.viewer.video.VideoItem

/**
 * Ordo Video's "Network" chip: the videos in the user's bookmarked server folders (SMB, SFTP, WebDAV, FTP), the
 * folder itself and one level of subfolders ("Filme/Titel (2020)/Titel.mkv"). Nothing is read until the chip is
 * tapped; the result is kept for [CACHE_MS] in this process. [VideoItem.path] holds the path's URI here.
 */
object VideoNetworkLibrary {
    private var cached: List<VideoItem>? = null
    private var cachedAtMs = 0L

    /** The bookmarked server folders; none means no chip. */
    fun folders(): List<Path> =
        Settings.BOOKMARK_DIRECTORIES.valueCompat.map { it.path }.filter { VideoBuffering.isNetwork(it.toUri().toString()) }

    fun cachedOrNull(): List<VideoItem>? = cached?.takeIf { System.currentTimeMillis() - cachedAtMs < CACHE_MS }

    /** Blocking (IO): lists every bookmarked server folder; a server that does not answer is skipped. */
    fun load(): List<VideoItem> {
        cachedOrNull()?.let { return it }
        val videos = ArrayList<VideoItem>()
        for (folder in folders()) {
            runCatching { collect(folder, depth = 0, into = videos) }
        }
        val result = videos.distinctBy { it.path }.sortedByDescending { it.dateAddedMs }
        cached = result
        cachedAtMs = System.currentTimeMillis()
        return result
    }

    private fun collect(folder: Path, depth: Int, into: MutableList<VideoItem>) {
        folder.newDirectoryStream().use { stream ->
            for (child in stream) {
                if (into.size >= MAX_VIDEOS) return
                val name = child.fileName?.toString() ?: continue
                if (name.startsWith('.')) continue
                val attributes = runCatching { child.readAttributes(BasicFileAttributes::class.java) }.getOrNull()
                    ?: continue
                if (attributes.isDirectory) {
                    if (depth < MAX_DEPTH) runCatching { collect(child, depth + 1, into) }
                } else if (isVideoName(name)) {
                    into += VideoItem(
                        child.toUri().toString(), name.substringBeforeLast('.').ifEmpty { name }, 0L,
                        attributes.size(), attributes.lastModifiedTime().toMillis()
                    )
                }
            }
        }
    }

    // The same guess the file list makes from a name.
    private fun isVideoName(name: String): Boolean = MimeType.guessFromPath(name).isVideo

    private const val CACHE_MS = 5 * 60_000L
    private const val MAX_DEPTH = 1
    private const val MAX_VIDEOS = 2000
}
