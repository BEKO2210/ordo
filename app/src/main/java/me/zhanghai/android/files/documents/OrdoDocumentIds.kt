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

    private const val PATH_PREFIX = "path:"

    fun idOf(file: File): String = PATH_PREFIX + file.canonicalPath

    /** The favourites that may be shown: existing folders outside every app-private place, canonical. */
    fun bases(favourites: List<File>): List<File> =
        favourites.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            .filter { it.isDirectory && !isPrivate(it) }
            .distinct()

    /** The file [id] names, or null when it is malformed or lies outside every one of [bases]. */
    fun fileOf(id: String, bases: List<File>): File? {
        if (!id.startsWith(PATH_PREFIX)) return null
        val raw = id.removePrefix(PATH_PREFIX)
        if (raw.isEmpty() || '\u0000' in raw || !raw.startsWith("/")) return null
        val file = runCatching { File(raw).canonicalFile }.getOrNull() ?: return null
        if (isPrivate(file)) return null
        return file.takeIf { bases.any { base -> isInside(file, base) } }
    }

    fun isInside(file: File, base: File): Boolean =
        file.path == base.path || file.path.startsWith(base.path.trimEnd('/') + "/")

    /** /data (other apps' and Ordo's own data) and every app's folders in shared storage stay hidden. */
    fun isPrivate(file: File): Boolean {
        val path = file.path
        return path == "/data" || path.startsWith("/data/") ||
            Regex("""/Android/(data|obb|media)(/|$)""").containsMatchIn(path)
    }
}
