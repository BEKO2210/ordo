/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import java.util.Comparator
import kotlin.random.Random
import me.zhanghai.android.files.player.isPlayableAudioMimeType

/**
 * One song as MediaStore lists it. [artist] and [album] are null when unknown; [track] is MediaStore's TRACK
 * (disc × 1000 + track number); [artUri] is the album's cover as a MediaStore URI.
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
    val artUri: String?
) {
    val folder: String
        get() = path.substringBeforeLast('/', "")
}

/** An album, an artist or a folder: [key] finds it again, [artist] is only set for albums. */
data class MusicGroup(val key: String, val name: String?, val artist: String?, val songs: List<MusicSong>)

/** Grouping, sorting and filtering of the music library; no Android in here, so it is unit-tested. */
class MusicLibrary(songs: List<MusicSong>, showHiddenFiles: Boolean, private val collator: Comparator<in String>) {
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
        MusicGroup(folder, folder.substringAfterLast('/'), null, list)
    }.sortedWith(compareBy<MusicGroup, String?>(byName) { it.name }.thenBy { it.key })

    private fun mostCommonArtist(songs: List<MusicSong>): String? =
        songs.mapNotNull { it.artist }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    companion object {
        fun matches(song: MusicSong, query: String): Boolean =
            query.isBlank() || listOfNotNull(song.title, song.artist, song.album).any { it.contains(query.trim(), true) }

        fun matches(group: MusicGroup, query: String): Boolean =
            query.isBlank() || listOfNotNull(group.name, group.artist, group.key.takeIf { it.startsWith('/') })
                .any { it.contains(query.trim(), true) }

        /** Every song exactly once, in random order. */
        fun shuffled(songs: List<MusicSong>, random: Random = Random.Default): List<MusicSong> = songs.shuffled(random)
    }
}
