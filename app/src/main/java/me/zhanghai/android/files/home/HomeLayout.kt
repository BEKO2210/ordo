/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

internal const val HOME_CATEGORY_TILE_COUNT = 9

private const val TABLET_MIN_WIDTH_DP = 600
private const val WIDE_MIN_WIDTH_DP = 840
// A phone on its side at 560 dpi is about 309 dp tall; 480 keeps tablets and portrait on the two-row grid.
private const val SHORT_WINDOW_HEIGHT_DP = 480

// home_list_padding and home_card_margin, phi_13 and phi_8
private const val LIST_PADDING_DP = 13
private const val CARD_MARGIN_DP = 8

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

/** Two rows of recents hide the categories on a short landscape phone; one row leaves them on screen. */
fun homeRecentGridRows(heightDp: Int): Int = if (heightDp < SHORT_WINDOW_HEIGHT_DP) 1 else 2

fun homeStorageColumns(widthDp: Int, storageCount: Int): Int =
    if (widthDp >= WIDE_MIN_WIDTH_DP && storageCount >= 2) 2 else 1

/**
 * In one row of nine on a tablet in portrait the tiles are too narrow for "337 · 0,90 GB": below 112 dp of tile
 * width they show the size alone, so no number is cut off.
 */
fun homeCategoryCompact(widthDp: Int): Boolean =
    homeCategoryColumns(widthDp) == 9 && (widthDp - 2 * LIST_PADDING_DP) / 9 - 2 * CARD_MARGIN_DP < 112

/**
 * How much glass the search bar shows, 0 to 1: it fades in over the first [edgePx] of content that lies under the
 * bar. At the top that is how far the page is scrolled down; at the bottom, how much page is left below.
 */
fun homeHeaderGlassAmount(atBottom: Boolean, scrollY: Int, scrollRange: Int, edgePx: Float): Float =
    ((if (atBottom) scrollRange - scrollY else scrollY) / edgePx).coerceIn(0f, 1f)

/**
 * A recent file's name for a narrow tile with two lines of about [maxChars] characters in all: a longer name loses
 * its middle, never its extension ("Rechnung_Mä…ember.pdf"), so the type stays readable.
 */
fun recentFileLabel(name: String, maxChars: Int): String {
    if (name.length <= maxChars || maxChars < 5) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 && name.length - it <= 6 } ?: name.length
    val tail = name.length - dot + (maxChars - (name.length - dot) - 1) / 2
    val head = maxChars - 1 - tail
    return name.take(head) + "…" + name.takeLast(tail)
}
