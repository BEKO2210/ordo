/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import java.util.Comparator
import kotlin.random.Random
import me.zhanghai.android.files.player.isPlayableAudioMimeType
import me.zhanghai.android.files.viewer.video.VideoLibrary

/**
 * One song as MediaStore lists it. [artist] and [album] are null when unknown; [track] is MediaStore's TRACK
 * (disc × 1000 + track number); [artUri] is the album's cover as a MediaStore URI. [genre] needs Android 11.
 */
data class MusicSong(
    val path: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumId: Long,
    val durationMs: Long,
    val track: Int,
    val mimeType: String,
    val artUri: String?,
    val genre: String? = null,
    /** 0 when unknown. */
    val year: Int = 0,
    val dateAddedMs: Long = 0,
    val dateModifiedMs: Long = 0
) {
    val folder: String
        get() = path.substringBeforeLast('/', "")
}

/** An album, an artist or a folder: [key] finds it again, [artist] is only set for albums. */
data class MusicGroup(val key: String, val name: String?, val artist: String?, val songs: List<MusicSong>)

/** Grouping, sorting and filtering of the music library; no Android in here, so it is unit-tested. */
class MusicLibrary(
    songs: List<MusicSong>,
    showHiddenFiles: Boolean,
    private val collator: Comparator<in String>,
    /** Folder names of a storage root, which would otherwise show as "0" or "1A2B-3C4D" (as in Ordo Video). */
    internalStorageName: String = "Internal storage",
    sdCardName: String = "SD card"
) {
    // Unknown names sort last.
    private val byName = Comparator<String?> { a, b ->
        when {
            a == null && b == null -> 0
            a == null -> 1
            b == null -> -1
            else -> collator.compare(a, b)
        }
    }
    private val byTitle = Comparator<MusicSong> { a, b -> collator.compare(a.title, b.title) }.thenBy { it.path }
    private val byTrack = compareBy<MusicSong> { it.track }.then(byTitle)

    /** Only what the player plays; hidden files (a "." folder or name) only when the user shows them. */
    val songs: List<MusicSong> = songs
        .filter { isPlayableAudioMimeType(it.mimeType) && (showHiddenFiles || !it.path.contains("/.")) }
        .distinctBy { it.path }
        .sortedWith(byTitle)

    val albums: List<MusicGroup> = this.songs.groupBy { it.albumId }.map { (id, list) ->
        MusicGroup(id.toString(), list.first().album, mostCommonArtist(list), list.sortedWith(byTrack))
    }.sortedWith(compareBy<MusicGroup, String?>(byName) { it.name })

    val artists: List<MusicGroup> = this.songs.groupBy { it.artist }.map { (artist, list) ->
        MusicGroup(artist.orEmpty(), artist, null, list)
    }.sortedWith(compareBy<MusicGroup, String?>(byName) { it.name })

    val folders: List<MusicGroup> = this.songs.groupBy { it.folder }.map { (folder, list) ->
        MusicGroup(folder, VideoLibrary.folderName(folder, internalStorageName, sdCardName), null, list)
    }.sortedWith(compareBy<MusicGroup, String?>(byName) { it.name }.thenBy { it.key })

    private fun mostCommonArtist(songs: List<MusicSong>): String? =
        songs.mapNotNull { it.artist }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    companion object {
        fun matches(song: MusicSong, query: String): Boolean =
            query.isBlank() || listOfNotNull(song.title, song.artist, song.album).any { it.contains(query.trim(), true) }

        fun matches(group: MusicGroup, query: String): Boolean =
            query.isBlank() || listOfNotNull(group.name, group.artist, group.key.takeIf { it.startsWith('/') })
                .any { it.contains(query.trim(), true) }

        /** Up to two letters for a placeholder cover: the first letter of the first two words ("Blue Hour" → "BH"). */
        fun initials(name: String?): String =
            name.orEmpty().split(' ', '-', '_', '.').mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
                .take(2).joinToString("").uppercase()

        /** The same name always picks the same of [size] colours (String.hashCode is fixed by the language). */
        fun paletteIndex(name: String?, size: Int): Int = Math.floorMod(name.orEmpty().hashCode(), size)

        /** Whether [path] is the session's current item ([mediaId] is the file's URI, e.g. with %20 for spaces). */
        fun isSameSong(path: String, mediaId: String?): Boolean =
            mediaId != null && runCatching { java.net.URI.create(mediaId) }.getOrNull()
                ?.takeIf { it.scheme == "file" }?.path == path

        /** Every song exactly once, in random order. */
        fun shuffled(songs: List<MusicSong>, random: Random = Random.Default): List<MusicSong> = songs.shuffled(random)
    }
}
