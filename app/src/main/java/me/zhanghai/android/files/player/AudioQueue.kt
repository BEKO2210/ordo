/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import java8.nio.file.Path

internal data class AudioQueue(val paths: List<Path>, val startIndex: Int)

internal const val AUDIO_QUEUE_SIZE_MAX = 1000

/**
 * Keeps the list's order, but never crosses the tapped file's folder boundary. A longer folder is cut to a window
 * around the tapped file so the queue stays small for the session and the persisted state.
 */
internal fun audioQueue(
    pathsInListOrder: List<Path>,
    tappedPath: Path,
    maxSize: Int = AUDIO_QUEUE_SIZE_MAX
): AudioQueue? {
    val paths = pathsInListOrder.filter { it.parent == tappedPath.parent }
    val startIndex = paths.indexOf(tappedPath)
    if (startIndex < 0) return null
    if (paths.size <= maxSize) return AudioQueue(paths, startIndex)
    val start = (startIndex - maxSize / 2).coerceIn(0, paths.size - maxSize)
    return AudioQueue(paths.subList(start, start + maxSize).toList(), startIndex - start)
}
