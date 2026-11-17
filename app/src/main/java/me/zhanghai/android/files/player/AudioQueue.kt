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
    val window = queueWindow(paths.size, startIndex, maxSize)
    return AudioQueue(paths.slice(window), startIndex - window.first)
}

/** The indices a queue of a list this long keeps: all of them, or [maxSize] around [startIndex]. */
internal fun queueWindow(size: Int, startIndex: Int, maxSize: Int = AUDIO_QUEUE_SIZE_MAX): IntRange {
    if (size <= maxSize) return 0 until size
    val start = (startIndex - maxSize / 2).coerceIn(0, size - maxSize)
    return start until start + maxSize
}
