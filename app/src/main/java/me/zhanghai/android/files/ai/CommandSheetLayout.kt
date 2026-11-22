/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

// Same 480 dp cut as homeRecentGridRows: a landscape phone at 560 dpi is about 309 dp tall.
private const val SHORT_SHEET_HEIGHT_DP = 480
private const val SETUP_ORB_DP = 144
private const val SETUP_ORB_SHORT_DP = 55

/** On a short landscape phone the 144 dp orb and the three check rows push the download off the screen. */
fun commandSetupCompact(heightDp: Int): Boolean = heightDp < SHORT_SHEET_HEIGHT_DP

fun commandSetupOrbDp(heightDp: Int): Int =
    if (commandSetupCompact(heightDp)) SETUP_ORB_SHORT_DP else SETUP_ORB_DP
