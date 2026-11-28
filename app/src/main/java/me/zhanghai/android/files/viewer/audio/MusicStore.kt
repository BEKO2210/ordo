/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.guessFromPath
import me.zhanghai.android.files.player.isPlayableAudioMimeType
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

/**
 * Every song MediaStore counts as music, an audiobook or a podcast, on every storage volume (the same volumes as the
 * home screen's categories). Rows of files that are gone are left out. Filtering and sorting happen in
 * [MusicLibrary].
 */
fun queryMusic(context: Context): List<MusicSong> {
    val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.getExternalVolumeNames(context).ifEmpty { setOf(MediaStore.VOLUME_EXTERNAL) }
    } else {
        setOf("external")
    }
    val projection = arrayOf(
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.TRACK,
        MediaStore.Audio.Media.MIME_TYPE,
        MediaStore.Audio.Media.YEAR,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.DATE_MODIFIED,
        MediaStore.Audio.Media.SIZE
    ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) arrayOf(MediaStore.Audio.Media.GENRE) else emptyArray()
    val mediaStoreSongs = volumes.flatMap { volume ->
        // Coil loads an album's cover from its MediaStore album URI (API 29+) or the old albumart URI.
        val artBase = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Albums.getContentUri(volume)
        } else {
            Uri.parse("content://media/$volume/audio/albumart")
        }
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.getContentUri(volume), projection, musicSelection(Build.VERSION.SDK_INT),
                null, null
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val path = cursor.getString(0) ?: continue
                        val albumId = cursor.getLong(4)
                        add(
                            MusicSong(
                                path = path,
                                title = cursor.getString(1)?.takeIf { it.isNotBlank() }
                                    ?: path.substringAfterLast('/').substringBeforeLast('.'),
                                artist = cursor.getString(2).known(),
                                album = cursor.getString(3).known(),
                                albumId = albumId,
                                durationMs = cursor.getLong(5),
                                track = cursor.getInt(6),
                                mimeType = cursor.getString(7).orEmpty(),
                                artUri = ContentUris.withAppendedId(artBase, albumId).toString(),
                                genre = if (projection.size > 12) cursor.getString(12).known() else null,
                                year = cursor.getInt(8),
                                // MediaStore counts both in seconds.
                                dateAddedMs = cursor.getLong(9) * 1000,
                                dateModifiedMs = cursor.getLong(10) * 1000,
                                sizeBytes = cursor.getLong(11)
                            )
                        )
                    }
                }
            }.orEmpty()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }.mapNotNull { song ->
        val file = File(song.path)
        if (file.exists()) song.withFileTimes(file.lastModified()) else null
    }
    val extraFolders = Settings.MUSIC_EXTRA_FOLDERS.valueCompat
    if (extraFolders.isEmpty()) return mediaStoreSongs
    val candidates = extraFolders.asSequence().flatMap(::additionalMusicCandidates)
    val mediaStorePaths = mediaStoreSongs.mapTo(HashSet()) { song ->
        runCatching { File(song.path).canonicalPath }.getOrDefault(File(song.path).absolutePath)
    }
    val additionalSongs = filterAdditionalMusic(candidates, mediaStorePaths)
        .map { candidate -> readAdditionalSong(File(candidate.path), candidate.mimeType) }
    return mediaStoreSongs + additionalSongs
}

/** The filesystem scan and this filtering run on queryMusic's background caller. */
internal data class AdditionalMusicCandidate(val path: String, val mimeType: String)

private fun additionalMusicCandidates(folder: String): Sequence<AdditionalMusicCandidate> {
    val root = File(folder)
    val rootPath = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
    val rootPrefix = rootPath + File.separator
    val visitedDirectories = HashSet<String>()
    return root.walkTopDown()
        // Do not follow a link out of the selected tree or loop through one back to an ancestor.
        .onEnter { directory ->
            val path = runCatching { directory.canonicalPath }.getOrNull() ?: return@onEnter false
            (path == rootPath || path.startsWith(rootPrefix)) && visitedDirectories.add(path)
        }
        .onFail { _, _ -> }
        .mapNotNull { file ->
            if (!file.isFile) return@mapNotNull null
            val path = runCatching { file.canonicalPath }.getOrNull() ?: return@mapNotNull null
            if (path != rootPath && !path.startsWith(rootPrefix)) return@mapNotNull null
            AdditionalMusicCandidate(path, MimeType.guessFromPath(path).value)
        }
}

internal fun filterAdditionalMusic(
    candidates: Sequence<AdditionalMusicCandidate>, mediaStorePaths: Set<String>
): List<AdditionalMusicCandidate> = candidates
    .filter { isPlayableAudioMimeType(it.mimeType) }
    .filterNot { it.path in mediaStorePaths }
    .distinctBy { it.path }
    .toList()

private fun readAdditionalSong(file: File, guessedMimeType: String): MusicSong {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        fun text(key: Int): String? = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
        val title = text(MediaMetadataRetriever.METADATA_KEY_TITLE)
            ?: file.nameWithoutExtension.takeIf { it.isNotBlank() }
            ?: file.name
        val artist = text(MediaMetadataRetriever.METADATA_KEY_ARTIST).known()
            ?: text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).known()
        val album = text(MediaMetadataRetriever.METADATA_KEY_ALBUM).known()
        val albumKey = album?.let { "$it\u0000${artist.orEmpty()}" } ?: file.parent.orEmpty()
        val mimeType = text(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            ?.takeIf(::isPlayableAudioMimeType) ?: guessedMimeType
        MusicSong(
            path = file.absolutePath,
            title = title,
            artist = artist,
            album = album,
            // MediaStore IDs are non-negative; keep synthetic albums in a separate, stable range.
            albumId = Long.MIN_VALUE + albumKey.hashCode().toUInt().toLong(),
            durationMs = text(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
            track = text(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')?.toIntOrNull() ?: 0,
            mimeType = mimeType,
            artUri = null,
            genre = text(MediaMetadataRetriever.METADATA_KEY_GENRE).known(),
            year = text(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull() ?: 0,
            dateModifiedMs = file.lastModified(),
            sizeBytes = file.length()
        )
    } catch (e: Exception) {
        e.printStackTrace()
        additionalSongWithoutTags(file, guessedMimeType)
    } finally {
        retriever.release()
    }
}

private fun additionalSongWithoutTags(file: File, mimeType: String): MusicSong = MusicSong(
    path = file.absolutePath,
    title = file.nameWithoutExtension.takeIf { it.isNotBlank() } ?: file.name,
    artist = null,
    album = null,
    albumId = Long.MIN_VALUE + file.parent.orEmpty().hashCode().toUInt().toLong(),
    durationMs = 0,
    track = 0,
    mimeType = mimeType,
    artUri = null,
    dateModifiedMs = file.lastModified(),
    sizeBytes = file.length()
)

/** Only playable library categories; unlike selecting all audio, this leaves system sounds and recordings out. */
internal fun musicSelection(sdkInt: Int): String {
    val columns = buildList {
        add(MediaStore.Audio.Media.IS_MUSIC)
        if (sdkInt >= Build.VERSION_CODES.Q) add(MediaStore.Audio.Media.IS_AUDIOBOOK)
        add(MediaStore.Audio.Media.IS_PODCAST)
    }
    return columns.joinToString(" OR ", prefix = "(", postfix = ")") { "$it != 0" }
}

// The file's own time has milliseconds; MediaStore's counts seconds, so two edits within one second stay apart.
internal fun MusicSong.withFileTimes(fileModifiedMs: Long): MusicSong =
    if (fileModifiedMs > 0) copy(dateModifiedMs = fileModifiedMs) else this

// MediaStore writes "<unknown>" where a tag is missing.
private fun String?.known(): String? = this?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
