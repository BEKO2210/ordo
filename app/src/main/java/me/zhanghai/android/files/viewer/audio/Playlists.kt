/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import java.io.File
import java.io.IOException
import java.util.UUID
import me.zhanghai.android.files.util.TextCharset

/**
 * Ordo Music's playlists and favourites: plain M3U8 files in Music/Playlists, so other music apps read them too.
 * Entries are written relative to the playlist's folder ("../Rock/song.mp3"); reading also takes absolute paths and
 * the plain .m3u files other apps write. Favourites are the playlist [FAVORITES_FILE].
 */
class PlaylistStore(val dir: File) {
    fun names(): List<String> =
        dir.listFiles { file -> file.isFile && file.extension.lowercase() in EXTENSIONS }
            ?.map { it.name }
            ?.sortedWith(compareBy<String> { it != FAVORITES_FILE }.thenBy(String.CASE_INSENSITIVE_ORDER) { it })
            .orEmpty()

    fun read(name: String): List<String> {
        val file = File(dir, name)
        if (!file.isFile) return emptyList()
        // Plain .m3u files of other apps are often Windows-1252: the shared detector, not UTF-8 alone.
        return M3u.parse(TextCharset.decode(file.readBytes()).text, dir)
    }

    fun write(name: String, paths: List<String>) {
        dir.mkdirs()
        val file = File(dir, name)
        // A copy first, then a rename: a failed write never leaves half a playlist.
        val temp = File(dir, ".ordo-${UUID.randomUUID()}.tmp")
        try {
            temp.writeText(M3u.write(paths, dir))
            if (!temp.renameTo(file)) throw IOException("Could not save $file")
        } finally {
            temp.delete()
        }
    }

    /** Adds what is not in it yet, at the end, in the given order. Returns how many were new. */
    fun add(name: String, paths: List<String>): Int {
        synchronized(READ_MODIFY_WRITE_LOCK) {
            val current = read(name)
            val new = paths.distinct().filter { it !in current }
            if (new.isNotEmpty() || !File(dir, name).exists()) write(name, current + new)
            return new.size
        }
    }

    fun remove(name: String, paths: Collection<String>) {
        synchronized(READ_MODIFY_WRITE_LOCK) { write(name, read(name).filter { it !in paths }) }
    }

    fun delete(name: String): Boolean = File(dir, name).delete()

    fun isFavorite(path: String): Boolean = path in read(FAVORITES_FILE)

    /** Turns the heart on or off; returns whether the song is a favourite now. */
    fun toggleFavorite(path: String): Boolean =
        synchronized(READ_MODIFY_WRITE_LOCK) {
            if (isFavorite(path)) {
                remove(FAVORITES_FILE, listOf(path))
                false
            } else {
                add(FAVORITES_FILE, listOf(path))
                true
            }
        }

    companion object {
        const val FAVORITES_FILE = "Favorites.m3u8"
        private val EXTENSIONS = setOf("m3u8", "m3u")
        private val READ_MODIFY_WRITE_LOCK = Any()

        /** A file name for a new playlist: what the user typed, without characters a file name can't have. */
        fun fileName(title: String): String? =
            title.trim().replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "").trim().trimStart('.')
                .takeIf { it.isNotEmpty() }?.take(100)?.let { "$it.m3u8" }

        fun title(fileName: String): String = fileName.substringBeforeLast('.')
    }
}

/**
 * M3U / M3U8 as every player writes it: "#EXTM3U", optional "#EXTINF" lines, then one path per line. Paths are
 * handled as strings (java.nio.file only exists from Android 8).
 */
object M3u {
    fun parse(text: String, baseDir: File): List<String> =
        text.removePrefix("\uFEFF").lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val path = line.removePrefix("file://").replace('\\', '/')
                normalize(if (path.startsWith("/")) path else "${baseDir.path}/$path")
            }
            .toList()

    fun write(paths: List<String>, baseDir: File): String = buildString {
        append("#EXTM3U\n")
        for (path in paths) append(relative(normalize(path), normalize(baseDir.path))).append('\n')
    }

    /** "/a/b/../c/./d" → "/a/c/d". */
    internal fun normalize(path: String): String {
        val parts = ArrayDeque<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/", prefix = "/")
    }

    /** [target] seen from [base], e.g. "/m/Rock/a.mp3" from "/m/Playlists" → "../Rock/a.mp3". */
    internal fun relative(target: String, base: String): String {
        val t = target.split('/').filter { it.isNotEmpty() }
        val b = base.split('/').filter { it.isNotEmpty() }
        var common = 0
        while (common < t.size && common < b.size && t[common] == b[common]) common++
        if (common == 0) return target
        return (List(b.size - common) { ".." } + t.drop(common)).joinToString("/")
    }
}
