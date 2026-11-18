/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.documents

import java.io.File

/**
 * Document ids of [OrdoDocumentsProvider] and the only way back from an id to a file. An id is never trusted: it is
 * resolved to a canonical path (no "..", no symlink escape) and must lie inside one of the shared folders the user
 * made a favourite in Ordo. App-private folders are never reachable, whatever the favourites say.
 */
object OrdoDocumentIds {
    /** The top level: the favourites themselves. */
    const val ROOT = "root"

    /** The top level of the second place: the files recently used in Ordo. */
    const val RECENT = "recent"

    /** At most this many recent files are offered. */
    const val RECENT_LIMIT = 50

    private const val PATH_PREFIX = "path:"

    fun idOf(file: File): String = PATH_PREFIX + file.canonicalPath

    /** The favourites that may be shown: existing folders outside every app-private place, canonical. */
    fun bases(favourites: List<File>): List<File> =
        favourites.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            .filter { it.isDirectory && !isPrivate(it) && !isHidden(it) }
            .distinct()

    /** Recent files that may be shown: existing files outside every app-private place, canonical, at most [limit]. */
    fun recentFiles(candidates: List<File>, limit: Int = RECENT_LIMIT): List<File> =
        candidates.asSequence()
            .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            .filter { it.isFile && !isPrivate(it) && !isHidden(it) }
            .distinct()
            .take(limit)
            .toList()

    /**
     * The file [id] names, or null when it is malformed or lies outside every one of [bases] and is none of the
     * [recent] files.
     */
    fun fileOf(id: String, bases: List<File>, recent: Collection<File> = emptyList()): File? {
        if (!id.startsWith(PATH_PREFIX)) return null
        val raw = id.removePrefix(PATH_PREFIX)
        if (raw.isEmpty() || '\u0000' in raw || !raw.startsWith("/")) return null
        val file = runCatching { File(raw).canonicalFile }.getOrNull() ?: return null
        // The same rules as the listings: what is never listed can never be opened by its id.
        if (isPrivate(file) || isHidden(file)) return null
        return file.takeIf { file in recent || bases.any { base -> isInside(file, base) } }
    }

    /** Pictures larger than this many pixels get no thumbnail at all: decoding them could exhaust memory. */
    const val MAX_THUMBNAIL_SOURCE_PIXELS = 50_000_000L

    /**
     * The power-of-two subsampling that brings an image of [width] x [height] down to [target] pixels or less on its
     * longer side: a thumbnail never decodes the full picture, also not a 100000 x 600 panorama.
     */
    fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > target.coerceAtLeast(1)) sample *= 2
        return sample
    }

    /** False for bounds that are missing or too large to decode safely ([MAX_THUMBNAIL_SOURCE_PIXELS]). */
    fun canThumbnail(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height <= MAX_THUMBNAIL_SOURCE_PIXELS

    /**
     * Whether a tree grant (ACTION_OPEN_DOCUMENT_TREE) may start at this top-level document. Only the bookmark place:
     * its content is the folders the user chose. "Recently used" changes by itself, so a grant on it would hand the
     * client every file that later becomes recent; recent files are granted one document at a time.
     */
    fun supportsTree(topDocumentId: String): Boolean = topDocumentId == ROOT

    fun isInside(file: File, base: File): Boolean =
        file.path == base.path || file.path.startsWith(base.path.trimEnd('/') + "/")

    /**
     * /data (other apps' and Ordo's own data) and every app's folders in shared storage stay hidden. Shared storage is
     * case-insensitive (FUSE), so "android/DATA" is the same folder as "Android/data": compared per path component,
     * ignoring case.
     */
    fun isPrivate(file: File): Boolean {
        val parts = file.path.split('/').filter { it.isNotEmpty() }.map { it.lowercase() }
        if (parts.firstOrNull() == "data") return true
        return parts.zipWithNext().any { (parent, child) -> parent == "android" && child in PRIVATE_IN_ANDROID }
    }

    /** A file or folder whose name, or any parent's name, starts with "." is never listed, so never reachable. */
    fun isHidden(file: File): Boolean = file.path.split('/').any { it.startsWith(".") }

    private val PRIVATE_IN_ANDROID = setOf("data", "obb", "media")
}
