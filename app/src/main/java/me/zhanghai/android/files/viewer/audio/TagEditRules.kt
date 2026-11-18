/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

/** Why the tag editor can only show a song. Kept apart from [SongTagIo], so Android 7 never loads jaudiotagger. */
enum class EditBlock { NONE, ANDROID, FORMAT, STORAGE }

object TagEditRules {
    /** jaudiotagger 3.0.1 needs java.nio.file, which Android has from 8.0 (API 26). */
    const val MIN_SDK = 26

    /** Opus, WAV and the rest are shown, not written: jaudiotagger 3.0.1 has no Opus writer. */
    val editableExtensions = setOf("mp3", "flac", "ogg", "m4a", "m4b", "mp4")

    fun isEditableFormat(extension: String): Boolean = extension.lowercase() in editableExtensions

    /** The first reason that wins: Android version, then a format Ordo can't write, then storage it may not change. */
    fun editBlock(sdkInt: Int, extensions: List<String>, allWritable: Boolean): EditBlock = when {
        sdkInt < MIN_SDK -> EditBlock.ANDROID
        extensions.any { !isEditableFormat(it) } -> EditBlock.FORMAT
        !allWritable -> EditBlock.STORAGE
        else -> EditBlock.NONE
    }
}
