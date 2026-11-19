/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * Every song MediaStore counts as music, on every storage volume (the same volumes as the home screen's categories).
 * Rows of files that are gone are left out. Filtering and sorting happen in [MusicLibrary].
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
        MediaStore.Audio.Media.DATE_MODIFIED
    ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) arrayOf(MediaStore.Audio.Media.GENRE) else emptyArray()
    return volumes.flatMap { volume ->
        // Coil loads an album's cover from its MediaStore album URI (API 29+) or the old albumart URI.
        val artBase = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Albums.getContentUri(volume)
        } else {
            Uri.parse("content://media/$volume/audio/albumart")
        }
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.getContentUri(volume), projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0",
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
                                genre = if (projection.size > 11) cursor.getString(11).known() else null,
                                year = cursor.getInt(8),
                                // MediaStore counts both in seconds.
                                dateAddedMs = cursor.getLong(9) * 1000,
                                dateModifiedMs = cursor.getLong(10) * 1000
                            )
                        )
                    }
                }
            }.orEmpty()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }.filter { File(it.path).exists() }
}

// MediaStore writes "<unknown>" where a tag is missing.
private fun String?.known(): String? = this?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
