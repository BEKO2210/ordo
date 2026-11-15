/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.filelist

object BatchRename {
    enum class Conflict { INVALID, EXISTS }

    // Renames run as separate parallel jobs, so any target that is not a plain name or that already exists
    // in the folder (even as another selected file) would fail halfway or move the file elsewhere.
    fun conflict(renames: List<Pair<String, String>>, exists: (String) -> Boolean): Conflict? {
        for ((old, new) in renames) {
            if (new.isEmpty() || '/' in new || '\u0000' in new) return Conflict.INVALID
            if (new != old && exists(new)) return Conflict.EXISTS
        }
        return null
    }

    const val NUMBER_PLACEHOLDER = "{n}"

    // "Urlaub_{n}" -> Urlaub_01.jpg, Urlaub_02.png …; without {n} the number is appended.
    fun newNames(files: List<Pair<String, Boolean>>, pattern: String, start: Int = 1): List<String> {
        val template = pattern.trim().let {
            if (NUMBER_PLACEHOLDER in it) it else "$it $NUMBER_PLACEHOLDER"
        }
        val width = maxOf(2, (start + files.size - 1).toString().length)
        return files.mapIndexed { index, (name, isDirectory) ->
            val number = (start + index).toString().padStart(width, '0')
            val dotIndex = name.lastIndexOf('.')
            val extension = if (!isDirectory && dotIndex > 0) name.substring(dotIndex) else ""
            template.replace(NUMBER_PLACEHOLDER, number) + extension
        }
    }
}
