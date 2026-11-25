/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import me.zhanghai.android.files.provider.linux.media.MediaScanner

/**
 * Asks MediaStore to drop rows of files that no longer exist, each path once per app run. MediaStore then reports
 * the deleted rows as a change; [isForgotten] lets the home screen ignore that echo, its numbers already left them out.
 */
object StaleMedia {
    private val requested = HashSet<String>()
    private val forgottenIds = HashSet<Long>()

    /** [paths] with the MediaStore row id of each. */
    fun forget(paths: List<String>, ids: List<Long>) {
        val fresh = remember(paths, ids)
        if (fresh.isEmpty()) return
        // One call for all paths: scanning a missing path removes its row.
        MediaScanner.scanPaths(fresh)
    }

    /** Records the rows and returns the paths not asked for before. */
    internal fun remember(paths: List<String>, ids: List<Long>): List<String> = synchronized(requested) {
        forgottenIds += ids
        paths.filter { requested.add(it) }
    }

    /** True for a change MediaStore reports about a row Ordo asked it to forget (the last segment is the row id). */
    fun isForgotten(rowId: Long?): Boolean = rowId != null && synchronized(requested) { rowId in forgottenIds }
}
