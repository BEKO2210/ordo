/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.filelist

import java8.nio.file.Path
import me.zhanghai.android.files.file.FileItem

internal fun focusedFileIndex(files: List<FileItem>, path: Path): Int =
    files.indexOfFirst { it.path == path }
