/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.filelist

/** Back walks up while there is a parent and the list is not at the folder it was opened on; then back leaves. */
fun <T> shouldBackNavigateUp(canNavigateUp: Boolean, currentPath: T, backStopPath: T?): Boolean =
    canNavigateUp && currentPath != backStopPath
