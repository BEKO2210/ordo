/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import android.net.Uri
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeTypeOrNull
import me.zhanghai.android.files.file.guessFromPath

data class RecentFile(
    val path: Path,
    val uri: Uri,
    val name: String,
    val mimeType: String?,
    val attributes: BasicFileAttributes?
)

/** MediaStore may omit a MIME type for otherwise valid files, so use the same extension fallback as the file list. */
internal fun recentMimeType(path: Path, mediaStoreMimeType: String?): MimeType =
    mediaStoreMimeType?.asMimeTypeOrNull()
        ?.takeUnless { it == MimeType.ANY || it == MimeType.GENERIC }
        ?: MimeType.guessFromPath(path.toString())

data class CategorySummary(val category: HomeCategory, val count: Long, val size: Long)
data class StorageSummary(
    val name: String,
    val path: Path,
    val used: Long,
    val total: Long,
    val removable: Boolean
)
data class QuickAccessItem(
    val name: String,
    val path: Path,
    val iconRes: Int,
    val hideIfEmpty: Boolean = false
)
data class HomeState(
    val recentFiles: List<RecentFile> = emptyList(),
    val categories: List<CategorySummary> = HomeCategory.entries.map { CategorySummary(it, 0, 0) },
    val storages: List<StorageSummary> = emptyList(),
    val quickAccess: List<QuickAccessItem> = emptyList(),
    val isLoading: Boolean = true,
    val appCount: Int? = null,
    val trashCount: Int? = null,
    val trashSize: Long = 0
)

/**
 * The visible part of a reload: tiles, recent files, storage and quick access. Loading ends here, so the pull
 * pictogram stops; app and trash counts keep their last values until [withCounts] (a large trash takes seconds).
 */
fun HomeState.withSections(loaded: HomeState): HomeState =
    copy(
        recentFiles = loaded.recentFiles, categories = loaded.categories, storages = loaded.storages,
        quickAccess = loaded.quickAccess, isLoading = false
    )

/** The slow part of a reload, applied without touching what [withSections] showed meanwhile. */
fun HomeState.withCounts(loaded: HomeState): HomeState =
    copy(appCount = loaded.appCount, trashCount = loaded.trashCount, trashSize = loaded.trashSize)

// One tile of the home grid: a category or a tool, all the same size.
class HomeTile(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int,
    @ColorRes val colorRes: Int,
    val summary: String?,
    val onClick: () -> Unit
)
