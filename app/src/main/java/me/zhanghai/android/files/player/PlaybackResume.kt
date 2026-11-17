/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import android.content.ContentResolver
import java.net.URI
import java8.nio.file.Files
import java8.nio.file.Paths

/**
 * The saved queue without the files that are gone. The start moves to the next file that is left (at position 0),
 * or stays where it was, with its position, when that file survived. Null when nothing is left.
 */
internal fun restorableState(
    state: PlaybackStateStore.State,
    exists: (String) -> Boolean
): PlaybackStateStore.State? {
    val kept = ArrayList<String>(state.paths.size)
    var startIndex = -1
    var currentSurvived = false
    state.paths.forEachIndexed { index, path ->
        if (!exists(path)) return@forEachIndexed
        if (startIndex < 0 && index >= state.currentIndex) {
            startIndex = kept.size
            currentSurvived = index == state.currentIndex
        }
        kept += path
    }
    if (kept.isEmpty()) return null
    if (startIndex < 0) startIndex = kept.lastIndex
    return state.copy(
        paths = kept,
        currentIndex = startIndex,
        positionMs = if (currentSurvived) state.positionMs else 0L
    )
}

/** Only Ordo's own paths are restored, never a `content://` item of an external launch. */
internal fun pathUriExists(pathUri: String): Boolean =
    try {
        val uri = URI.create(pathUri)
        uri.scheme != ContentResolver.SCHEME_CONTENT && Files.exists(Paths.get(uri))
    } catch (_: Exception) {
        false
    }
