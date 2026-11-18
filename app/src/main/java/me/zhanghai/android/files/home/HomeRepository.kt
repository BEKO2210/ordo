/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import java.io.IOException
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.provider.common.readAttributes

import android.content.ContentUris
import android.content.Context
import android.content.pm.ApplicationInfo
import me.zhanghai.android.files.trash.Trash
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.directoryCompat
import me.zhanghai.android.files.compat.getDescriptionCompat
import me.zhanghai.android.files.compat.isRemovableCompat
import me.zhanghai.android.files.compat.storageVolumesCompat
import me.zhanghai.android.files.navigation.StandardDirectoriesLiveData
import me.zhanghai.android.files.player.isPlayableAudioMimeType
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

class HomeRepository(private val context: Context) {
    companion object {
        // Enough for the horizontal row; the grid shows the first six.
        private const val RECENT_COUNT = 20

        // Folder of a file relative to the storage root it lies on (primary or SD card/USB).
        fun relativeDir(data: String, roots: List<String>): String {
            val root = roots.firstOrNull { data.startsWith("$it/") }
            return (if (root != null) data.removePrefix("$root/") else data).substringBeforeLast('/', "")
        }
    }

    // quickAccess must be read on the main thread: its LiveData sources initialize with setValue().
    // Still loading: the app and trash counts follow from [loadCounts].
    fun load(quickAccess: List<QuickAccessItem>, storages: List<StorageSummary>): HomeState {
        val rows = queryRows()
        val recentFiles = newestFiles(rows, RECENT_COUNT, { it.modified }) { java.io.File(it.data).isFile }.map {
            val path = Paths.get(it.data)
            val attributes = try {
                path.readAttributes(BasicFileAttributes::class.java)
            } catch (e: IOException) {
                null
            }
            RecentFile(path, it.uri, it.name, it.mimeType, attributes)
        }.toList()
        val summaries = HomeCategory.entries.map { category ->
            val matching = rows.filter { category in it.categories }
            CategorySummary(category, matching.size.toLong(), matching.sumOf { it.size })
        }
        // Like Samsung: empty system folders such as Alarms or Ringtones only add noise.
        val visibleQuickAccess = quickAccess.filter { item ->
            !item.hideIfEmpty || java.io.File(item.path.toString()).list()?.isNotEmpty() == true
        }
        return HomeState(recentFiles, summaries, storages, visibleQuickAccess, true)
    }

    // A large trash takes seconds to measure, so these come after the tiles and recent files.
    fun loadCounts(state: HomeState): HomeState {
        val trash = Trash.summary()
        @Suppress("DEPRECATION")
        val appCount = context.packageManager.getInstalledApplications(0)
            .count { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
        return state.copy(
            isLoading = false, appCount = appCount, trashCount = trash.count, trashSize = trash.size
        )
    }

    fun loadCategoryPaths(category: HomeCategory, query: String = ""): List<Path> =
        queryRows().asSequence()
            .filter { category in it.categories && it.name.contains(query, ignoreCase = true) }
            .map { Paths.get(it.data) }
            .toList()

    /** The files home lists under "Recent files", newest first: also what Ordo offers in the system file picker. */
    fun loadRecentFiles(limit: Int): List<java.io.File> =
        queryRows().asSequence()
            .sortedByDescending { it.modified }
            .map { java.io.File(it.data) }
            .filter { it.isFile }
            .take(limit)
            .toList()

    /** The Audio category the home screen shows, restricted to what the player plays, recently modified first. */
    fun loadRecentAudio(limit: Int): List<Path> =
        queryRows().asSequence()
            .filter { HomeCategory.AUDIO in it.categories && isPlayableAudioMimeType(it.mimeType.orEmpty()) }
            .sortedByDescending { it.modified }
            .take(limit)
            .map { Paths.get(it.data) }
            .toList()

    private fun queryRows(): List<MediaRow> {
        val showHiddenFiles = Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat
        val resolver = context.contentResolver
        @Suppress("DEPRECATION")
        val externalStoragePath = Environment.getExternalStorageDirectory().path
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
        val roots = (listOf(externalStoragePath) +
            storageManager.storageVolumesCompat.mapNotNull { it.directoryCompat?.path })
            .distinct().sortedByDescending { it.length }
        val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(context).ifEmpty { setOf(MediaStore.VOLUME_EXTERNAL) }
        } else {
            setOf("external")
        }
        return volumes.flatMap { volume ->
            val uri = MediaStore.Files.getContentUri(volume)
            val projection = mutableListOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                projection += MediaStore.Files.FileColumns.RELATIVE_PATH
            }
            try {
                resolver.query(uri, projection.toTypedArray(), null, null, null)?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                    val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                    val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                    val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                    val relativeIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                    buildList {
                        while (cursor.moveToNext()) {
                            val data = cursor.getString(dataIndex) ?: continue
                            val name = cursor.getString(nameIndex) ?: data.substringAfterLast('/')
                            val mime = cursor.getString(mimeIndex)
                            val relative = if (relativeIndex >= 0) cursor.getString(relativeIndex) else {
                                relativeDir(data, roots)
                            }
                            add(
                                MediaRow(
                                    data, ContentUris.withAppendedId(uri, cursor.getLong(idIndex)),
                                    name, mime, cursor.getLong(sizeIndex).coerceAtLeast(0),
                                    cursor.getLong(modifiedIndex),
                                    HomeCategory.classify(mime, name, relative)
                                )
                            )
                        }
                    }
                }.orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
        }.distinctBy { it.data }
            // Hidden files (".thumbnails", ".trash" …) only count when the user shows hidden files.
            .filter { showHiddenFiles || !it.data.contains("/.") }
            .let(::dropMissing)
    }

    // MediaStore keeps rows of files that are gone, e.g. the contents of a folder moved to the trash, or files whose
    // scan was cut short. They are not counted or listed, and MediaStore is asked once to forget them.
    // shortcut: one stat per row, fine for tens of thousands of media files; batch by folder if libraries grow larger.
    private fun dropMissing(rows: List<MediaRow>): List<MediaRow> {
        val (present, missing) = rows.partition { java.io.File(it.data).exists() }
        StaleMedia.forget(missing.map { it.data }, missing.map { android.content.ContentUris.parseId(it.uri) })
        return present
    }

    fun loadStorages(): List<StorageSummary> {
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
        return storageManager.storageVolumesCompat.mapNotNull { volume ->
            val directory = volume.directoryCompat ?: return@mapNotNull null
            val total = directory.totalSpace.coerceAtLeast(0)
            val free = directory.freeSpace.coerceAtLeast(0)
            StorageSummary(
                volume.getDescriptionCompat(context),
                Paths.get(directory.path),
                (total - free).coerceAtLeast(0), total, volume.isRemovableCompat
            )
        }.distinctBy { it.path }
    }

    fun loadQuickAccess(): List<QuickAccessItem> {
        @Suppress("DEPRECATION")
        val external = Environment.getExternalStorageDirectory()
        val standard = StandardDirectoriesLiveData.valueCompat.filter { it.isEnabled }.map {
            QuickAccessItem(
                it.getTitle(context), Paths.get(external.resolve(it.relativePath).path), it.iconRes,
                hideIfEmpty = true
            )
        }
        val bookmarks = Settings.BOOKMARK_DIRECTORIES.valueCompat.map {
            QuickAccessItem(it.name, it.path, R.drawable.directory_icon_white_24dp)
        }
        return (standard + bookmarks).distinctBy { it.path }
    }

    private data class MediaRow(
        val data: String,
        val uri: android.net.Uri,
        val name: String,
        val mimeType: String?,
        val size: Long,
        val modified: Long,
        val categories: Set<HomeCategory>
    )
}

/**
 * The [count] newest of [rows] that are files. Sorted first, so [isFile] (one stat on shared storage) runs only until
 * [count] files are found, not once per row: 25,825 rows on the Galaxy Tab S9 Ultra.
 */
internal fun <T> newestFiles(rows: List<T>, count: Int, modified: (T) -> Long, isFile: (T) -> Boolean): List<T> =
    rows.asSequence().sortedByDescending(modified).filter(isFile).take(count).toList()
