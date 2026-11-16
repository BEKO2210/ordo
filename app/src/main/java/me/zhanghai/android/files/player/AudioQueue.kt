/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import java8.nio.file.Path

internal data class AudioQueue(val paths: List<Path>, val startIndex: Int)

/** Keeps the list's order, but never crosses the tapped file's folder boundary. */
internal fun audioQueue(pathsInListOrder: List<Path>, tappedPath: Path): AudioQueue? {
    val paths = pathsInListOrder.filter { it.parent == tappedPath.parent }
    val startIndex = paths.indexOf(tappedPath)
    return if (startIndex >= 0) AudioQueue(paths, startIndex) else null
}
