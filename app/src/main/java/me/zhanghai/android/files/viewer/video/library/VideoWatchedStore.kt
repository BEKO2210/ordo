/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.library

import android.content.Context
import androidx.core.content.edit

/**
 * "Watched" marks of Ordo Video (SharedPreferences `video_watched`): key path + size ([VideoShelves.watchedKey]),
 * value the time it was marked. Set by the player at 95 % or less than 10 s left, or by hand in the library.
 */
// shortcut: no cap on the number of marks (a few bytes each); add one like VideoResumeStore's if it ever grows large.
class VideoWatchedStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun all(): Map<String, Long> = preferences.all.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }
        .toMap()

    fun set(keys: Collection<String>, watched: Boolean, nowMs: Long = System.currentTimeMillis()) {
        preferences.edit {
            keys.forEach { if (watched) putLong(it, nowMs) else remove(it) }
        }
    }

    /** From the player: [resumeKey] is its resume key; marks the video once it is finished. */
    fun onProgress(resumeKey: String, positionMs: Long, durationMs: Long) {
        if (!VideoShelves.isFinished(positionMs, durationMs)) return
        VideoShelves.watchedKeyFromResumeKey(resumeKey)?.let { set(listOf(it), true) }
    }

    companion object {
        private const val PREFERENCES_NAME = "video_watched"
    }
}
