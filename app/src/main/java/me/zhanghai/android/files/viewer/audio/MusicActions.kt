/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand
import java8.nio.file.Paths
import kotlin.random.Random
import me.zhanghai.android.files.player.AudioPlaybackService
import me.zhanghai.android.files.player.AudioPlayerCommands
import me.zhanghai.android.files.player.AudioSettingsStore
import me.zhanghai.android.files.player.EqualizerPreset
import me.zhanghai.android.files.player.PlayHistoryStore
import me.zhanghai.android.files.player.queueWindow

/**
 * Which songs to play. Every set field narrows the choice; text matches ignore case and match parts ("beat" finds
 * "The Beatles"). [format] is the file extension ("flac"). [playlist] is a playlist's file name.
 */
data class MusicFilter(
    val query: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val folder: String? = null,
    val playlist: String? = null,
    val favorites: Boolean = false,
    val recentlyPlayed: Boolean = false,
    val mostPlayed: Boolean = false,
    val addedAfterMs: Long? = null,
    val modifiedAfterMs: Long? = null,
    val minDurationMs: Long? = null,
    val maxDurationMs: Long? = null,
    val format: String? = null,
    val limit: Int? = null
)

/**
 * Everything a voice command or the coming on-device assistant may ask of Ordo's music: one interface, the same
 * code paths as the buttons.
 */
interface MusicActions {
    /** Plays what [filter] selects (shuffled on request) and returns how many songs it queued; 0 changes nothing. */
    fun play(filter: MusicFilter, shuffle: Boolean = false): Int
    fun playNext(paths: List<String>)
    fun addToQueue(paths: List<String>)
    /** 0 minutes and no [endOfTrack] switch the timer off. */
    fun setSleepTimer(minutes: Int, endOfTrack: Boolean = false)
    fun setKlangPreset(preset: EqualizerPreset)
}

/** What a selection is made from: the library's songs, the play history and the playlists (name to paths). */
class MusicCatalog(
    val songs: List<MusicSong>,
    val history: List<PlayHistoryStore.Entry> = emptyList(),
    val playlists: Map<String, List<String>> = emptyMap()
)

/** The selection rules, without Android: unit-tested. */
object MusicSelection {
    fun select(catalog: MusicCatalog, filter: MusicFilter): List<MusicSong> {
        fun String?.has(part: String?) = part.isNullOrBlank() || (this != null && contains(part.trim(), true))
        var songs = catalog.songs.filter { song ->
            (filter.query.isNullOrBlank() ||
                listOfNotNull(song.title, song.artist, song.album, song.genre).any { it.has(filter.query) }) &&
                song.artist.has(filter.artist) && song.album.has(filter.album) && song.genre.has(filter.genre) &&
                (filter.year == null || song.year == filter.year) &&
                (filter.folder == null || song.folder == filter.folder.trimEnd('/') ||
                    song.path.startsWith(filter.folder.trimEnd('/') + "/")) &&
                (filter.addedAfterMs == null || song.dateAddedMs >= filter.addedAfterMs) &&
                (filter.modifiedAfterMs == null || song.dateModifiedMs >= filter.modifiedAfterMs) &&
                (filter.minDurationMs == null || song.durationMs >= filter.minDurationMs) &&
                (filter.maxDurationMs == null || song.durationMs <= filter.maxDurationMs) &&
                (filter.format == null || song.path.substringAfterLast('.').equals(filter.format.trimStart('.'), true))
        }
        if (filter.favorites) songs = inOrder(songs, catalog.playlists[PlaylistStore.FAVORITES_FILE].orEmpty())
        if (filter.playlist != null) songs = inOrder(songs, catalog.playlists[filter.playlist].orEmpty())
        when {
            filter.recentlyPlayed -> songs = inOrder(songs, PlayHistoryStore.recent(catalog.history).map { it.path() })
            filter.mostPlayed -> songs = inOrder(songs, PlayHistoryStore.mostPlayed(catalog.history).map { it.path() })
            // "What's new" comes newest first.
            filter.addedAfterMs != null -> songs = songs.sortedByDescending { it.dateAddedMs }
            filter.modifiedAfterMs != null -> songs = songs.sortedByDescending { it.dateModifiedMs }
        }
        return filter.limit?.let { songs.take(it.coerceAtLeast(0)) } ?: songs
    }

    /** The songs listed in [paths], in that order. */
    private fun inOrder(songs: List<MusicSong>, paths: List<String>): List<MusicSong> {
        val byPath = songs.associateBy { it.path }
        return paths.distinct().mapNotNull { byPath[it] }
    }

    private fun PlayHistoryStore.Entry.path(): String =
        runCatching { java.net.URI.create(pathUri).path }.getOrNull() ?: pathUri

    /** Where queued songs go: right after the current one ([next]) or at the end. */
    fun insertIndex(next: Boolean, currentIndex: Int, count: Int): Int =
        if (next) (currentIndex + 1).coerceIn(0, count) else count
}

/**
 * [MusicActions] on a connected session controller of Ordo's own ([player] and [send], e.g. a MediaController and
 * its sendCustomCommand). [catalog] is read when needed; call [play] off the main thread when it queries MediaStore.
 */
class ControllerMusicActions(
    private val player: Player,
    private val send: (SessionCommand, Bundle) -> Unit,
    private val catalog: () -> MusicCatalog,
    private val random: Random = Random.Default
) : MusicActions {
    override fun play(filter: MusicFilter, shuffle: Boolean): Int {
        var songs = MusicSelection.select(catalog(), filter)
        if (songs.isEmpty()) return 0
        if (shuffle) songs = MusicLibrary.shuffled(songs, random)
        songs = songs.slice(queueWindow(songs.size, 0))
        player.setMediaItems(songs.map { item(it.path) }, 0, 0L)
        player.prepare()
        player.play()
        return songs.size
    }

    override fun playNext(paths: List<String>) = queue(paths, next = true)

    override fun addToQueue(paths: List<String>) = queue(paths, next = false)

    private fun queue(paths: List<String>, next: Boolean) {
        if (paths.isEmpty()) return
        player.addMediaItems(
            MusicSelection.insertIndex(next, player.currentMediaItemIndex, player.mediaItemCount), paths.map(::item)
        )
    }

    override fun setSleepTimer(minutes: Int, endOfTrack: Boolean) = send(
        AudioPlayerCommands.SLEEP_TIMER,
        bundleOf(AudioPlayerCommands.EXTRA_SLEEP_MINUTES to minutes, AudioPlayerCommands.EXTRA_END_OF_TRACK to endOfTrack)
    )

    override fun setKlangPreset(preset: EqualizerPreset) {
        AudioSettingsStore.write(
            AudioSettingsStore.read().copy(equalizerEnabled = true, preset = preset, bandGainsDb = preset.gainsDb)
        )
        send(AudioPlayerCommands.APPLY_SETTINGS, Bundle.EMPTY)
    }

    private fun item(path: String): MediaItem = AudioPlaybackService.mediaItem(Paths.get(path).toUri().toString())
}
