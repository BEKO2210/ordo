/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.net.URI

/** One video as MediaStore lists it; [size] is the file's own, so it matches the player's resume key. */
data class VideoItem(
    val path: String,
    val title: String,
    val durationMs: Long,
    val size: Long,
    val dateAddedMs: Long,
    /** MediaStore's row, for its cached thumbnail; null in tests. */
    val contentUri: String? = null
) {
    val folder: String
        get() = path.substringBeforeLast('/', "")
}

data class VideoFolder(val path: String, val name: String, val videos: List<VideoItem>)

data class ContinueItem(val video: VideoItem, val positionMs: Long)

/** Sorting, folders and "Continue watching" of Ordo Video; no Android in here, so it is unit-tested. */
class VideoLibrary(videos: List<VideoItem>, showHiddenFiles: Boolean, private val collator: Comparator<in String>) {
    /** Newest first; hidden files (a "." folder or name) only when the user shows them, as in the file list. */
    val videos: List<VideoItem> = videos
        .filter { showHiddenFiles || !it.path.contains("/.") }
        .distinctBy { it.path }
        .sortedWith(compareByDescending<VideoItem> { it.dateAddedMs }.thenBy { it.path })

    /** Folders in the order of their newest video. */
    val folders: List<VideoFolder> = this.videos.groupBy { it.folder }
        .map { (folder, list) -> VideoFolder(folder, folder.substringAfterLast('/'), list) }

    /**
     * What the player hands over as its queue: the folder's videos by name, the way a series is numbered
     * ("Episode 2" before "Episode 10" needs the file list's own sorting; the collator is close enough).
     */
    fun queueOf(video: VideoItem): List<VideoItem> =
        videos.filter { it.folder == video.folder }
            .sortedWith(compareBy<VideoItem, String>(collator) { it.path.substringAfterLast('/') }.thenBy { it.path })

    /** Saved places of videos still here (same path and size), last watched first. */
    fun continueWatching(entries: List<VideoResumeStore.Entry>, max: Int = CONTINUE_MAX): List<ContinueItem> {
        val byPath = videos.associateBy { it.path }
        return entries.sortedByDescending { it.savedAtMs }
            .mapNotNull { entry ->
                val path = runCatching { URI.create(entry.pathUri) }.getOrNull()?.takeIf { it.scheme == "file" }?.path
                byPath[path]?.takeIf { it.size == entry.size }?.let { ContinueItem(it, entry.positionMs) }
            }
            .distinctBy { it.video.path }
            .take(max)
    }

    companion object {
        const val CONTINUE_MAX = 20
    }
}

/** Every video MediaStore knows, on every storage volume; rows of files that are gone are left out. */
fun queryVideos(context: Context): List<VideoItem> {
    val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.getExternalVolumeNames(context).ifEmpty { setOf(MediaStore.VOLUME_EXTERNAL) }
    } else {
        setOf("external")
    }
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DATA,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.DATE_ADDED
    )
    return volumes.flatMap { volume ->
        val base = MediaStore.Video.Media.getContentUri(volume)
        try {
            context.contentResolver.query(base, projection, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val path = cursor.getString(1) ?: continue
                        val file = File(path)
                        if (!file.exists()) continue
                        val name = cursor.getString(2)?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/')
                        add(
                            VideoItem(
                                path = path,
                                title = name.substringBeforeLast('.').ifEmpty { name },
                                durationMs = cursor.getLong(3),
                                size = file.length(),
                                // MediaStore counts in seconds.
                                dateAddedMs = cursor.getLong(4) * 1000,
                                contentUri = ContentUris.withAppendedId(base, cursor.getLong(0)).toString()
                            )
                        )
                    }
                }
            }.orEmpty()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}

/** The video's thumbnail from the platform: MediaStore's cached one (API 29+), else a frame of the file. */
@Suppress("DEPRECATION")
fun loadVideoThumbnail(context: Context, video: VideoItem, width: Int, height: Int): android.graphics.Bitmap? =
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && video.contentUri != null) {
            context.contentResolver.loadThumbnail(Uri.parse(video.contentUri), android.util.Size(width, height), null)
        } else {
            android.media.ThumbnailUtils.createVideoThumbnail(video.path, MediaStore.Video.Thumbnails.MINI_KIND)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
