/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

enum class FileItemSize(
    val listItemHeightDp: Int,
    val listThumbnailSizeDp: Int,
    val listIconSizeDp: Int,
    private val gridColumnWidthDp: Int
) {
    SMALL(56, 40, 24, 90),
    MEDIUM(72, 40, 24, 120),
    LARGE(88, 56, 32, 180);

    fun gridSpanCount(widthDp: Int): Int = (widthDp / gridColumnWidthDp).coerceAtLeast(2)

    val isCompactGrid: Boolean
        get() = this != LARGE

    companion object {
        // Start and end offset of a tile inside its grid cell, so that all tiles are equally wide
        // with [spacing] at both screen edges and between them.
        fun gridItemOffsets(column: Int, spanCount: Int, spacing: Int): Pair<Int, Int> =
            spacing - column * spacing / spanCount to (column + 1) * spacing / spanCount
    }
}

// Saved by ordinal, so only append new values.
enum class FileViewType(val isGrid: Boolean, val size: FileItemSize) {
    LIST(false, FileItemSize.MEDIUM),
    GRID(true, FileItemSize.MEDIUM),
    LIST_SMALL(false, FileItemSize.SMALL),
    LIST_LARGE(false, FileItemSize.LARGE),
    GRID_SMALL(true, FileItemSize.SMALL),
    GRID_LARGE(true, FileItemSize.LARGE);

    fun with(isGrid: Boolean = this.isGrid, size: FileItemSize = this.size): FileViewType =
        entries.first { it.isGrid == isGrid && it.size == size }
}
