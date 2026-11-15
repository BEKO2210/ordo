/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import android.media.MediaScannerConnection
import me.zhanghai.android.files.app.application

/** Asks MediaStore to drop rows of files that no longer exist, each path once per app run. */
object StaleMedia {
    private val requested = HashSet<String>()

    fun forget(paths: List<String>) {
        val fresh = synchronized(requested) { paths.filter { requested.add(it) } }
        if (fresh.isEmpty()) return
        // One call for all paths: scanning a missing path removes its row.
        MediaScannerConnection.scanFile(application, fresh.toTypedArray(), null, null)
    }
}
