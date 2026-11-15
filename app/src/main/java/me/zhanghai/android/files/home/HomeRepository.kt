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
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

class HomeRepository(private val context: Context) {
    companion object {
        // Enough for the horizontal row; the grid shows the first six.
        private const val RECENT_COUNT = 20
    }

    // quickAccess must be read on the main thread: its LiveData sources initialize with setValue().
    fun load(quickAccess: List<QuickAccessItem>): HomeState {
        val rows = queryRows()
        val recentFiles = rows.asSequence().filter { java.io.File(it.data).isFile }
            .sortedByDescending { it.modified }.take(RECENT_COUNT).map {
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
        val trashEntries = Trash.entries()
        @Suppress("DEPRECATION")
        val appCount = context.packageManager.getInstalledApplications(0)
            .count { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
        // Like Samsung: empty system folders such as Alarms or Ringtones only add noise.
        val visibleQuickAccess = quickAccess.filter { item ->
            !item.hideIfEmpty || java.io.File(item.path.toString()).list()?.isNotEmpty() == true
        }
        return HomeState(
            recentFiles, summaries, loadStorages(), visibleQuickAccess, false, appCount,
            trashEntries.size, trashEntries.sumOf { entry ->
                entry.file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
        )
    }

    fun loadCategoryPaths(category: HomeCategory, query: String = ""): List<Path> =
        queryRows().asSequence()
            .filter { category in it.categories && it.name.contains(query, ignoreCase = true) }
            .map { Paths.get(it.data) }
            .toList()

    private fun queryRows(): List<MediaRow> {
        val showHiddenFiles = Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat
        val resolver = context.contentResolver
        @Suppress("DEPRECATION")
        val externalStoragePath = Environment.getExternalStorageDirectory().path
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
                                data.removePrefix("$externalStoragePath/").substringBeforeLast('/', "")
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
        StaleMedia.forget(missing.map { it.data })
        return present
    }

    private fun loadStorages(): List<StorageSummary> {
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
