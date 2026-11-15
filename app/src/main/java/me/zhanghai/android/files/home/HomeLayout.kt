/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

internal const val HOME_CATEGORY_TILE_COUNT = 9

private const val TABLET_MIN_WIDTH_DP = 600
private const val WIDE_MIN_WIDTH_DP = 840

/** Nine tiles only tile evenly in 1, 3, or 9 columns; phones stay at 3, wide screens get one row of 9. */
fun homeCategoryColumns(widthDp: Int): Int = if (widthDp < WIDE_MIN_WIDTH_DP) 3 else 9

fun homeRecentColumns(widthDp: Int): Int = when {
    widthDp < TABLET_MIN_WIDTH_DP -> 4
    widthDp < WIDE_MIN_WIDTH_DP -> 6
    else -> 8
}

fun homeQuickAccessColumns(widthDp: Int): Int = when {
    widthDp < TABLET_MIN_WIDTH_DP -> 3
    widthDp < WIDE_MIN_WIDTH_DP -> 5
    else -> 8
}

fun homeStorageColumns(widthDp: Int, storageCount: Int): Int =
    if (widthDp >= WIDE_MIN_WIDTH_DP && storageCount >= 2) 2 else 1

/**
 * In one row of nine on a tablet in portrait the tiles are too narrow for "337 · 0,90 GB": below 112 dp of tile
 * width they show the size alone, so no number is cut off.
 */
fun homeCategoryCompact(widthDp: Int): Boolean =
    homeCategoryColumns(widthDp) == 9 && (widthDp - 24) / 9 - 8 < 112
