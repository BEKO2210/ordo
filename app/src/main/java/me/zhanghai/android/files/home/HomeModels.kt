/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import android.net.Uri
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes

data class RecentFile(
    val path: Path,
    val uri: Uri,
    val name: String,
    val mimeType: String?,
    val attributes: BasicFileAttributes?
)
data class CategorySummary(val category: HomeCategory, val count: Long, val size: Long)
data class StorageSummary(
    val name: String,
    val path: Path,
    val used: Long,
    val total: Long,
    val removable: Boolean
)
data class QuickAccessItem(val name: String, val path: Path, val iconRes: Int)
data class HomeState(
    val recentFiles: List<RecentFile> = emptyList(),
    val categories: List<CategorySummary> = HomeCategory.entries.map { CategorySummary(it, 0, 0) },
    val storages: List<StorageSummary> = emptyList(),
    val quickAccess: List<QuickAccessItem> = emptyList(),
    val isLoading: Boolean = true
)
