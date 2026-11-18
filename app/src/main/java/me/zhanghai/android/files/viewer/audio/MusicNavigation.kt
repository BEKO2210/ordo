/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

/** The stable ids of Ordo Music's side menu, in order; null is a divider. */
object MusicNavigation {
    // NavigationListAdapter gives its dividers the ids 0, 1, 2 …: the entries stay clear of them.
    private const val ID_BASE = 1_000L

    val IDS: List<Long?> = listOf(
        ID_BASE + 1, ID_BASE + 2, ID_BASE + 3, null, ID_BASE + 4, ID_BASE + 5, null, ID_BASE + 6, ID_BASE + 7
    )

    /** The id NavigationListAdapter.getItemId gives each row: the entry's own, or the number of dividers before it. */
    fun adapterIds(ids: List<Long?>): List<Long> =
        ids.mapIndexed { position, id -> id ?: ids.subList(0, position).count { it == null }.toLong() }
}
