/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import android.content.ContentResolver
import android.content.Context
import androidx.core.content.edit
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.viewer.video.VideoResumeStore
import me.zhanghai.android.files.viewer.video.vault.VaultVideos

/**
 * The "Bild" sheet's adjustments per video, like the subtitle choice: only Ordo's own paths, never a Tresor video,
 * another app's `content://` or anything while "Keine Stellen merken" is on. At most [MAX_ENTRIES], oldest first out;
 * "Clear Continue watching" forgets them too.
 */
class PictureAdjustStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(mediaId: String): PictureAdjustments? =
        if (remembers(mediaId)) {
            PictureAdjustments.decode(preferences.getString(KEY_PREFIX + mediaId, null)?.substringBefore(FIELD))
        } else {
            null
        }

    fun write(mediaId: String, value: PictureAdjustments, nowMs: Long = System.currentTimeMillis()) {
        if (!remembers(mediaId)) return
        val key = KEY_PREFIX + mediaId
        if (value == PictureAdjustments()) {
            preferences.edit { remove(key) }
            return
        }
        val evicted = if (preferences.contains(key)) {
            emptyList()
        } else {
            VideoResumeStore.keysToEvict(
                preferences.all.filterKeys { it.startsWith(KEY_PREFIX) }
                    .mapValues { (it.value as? String)?.substringAfter(FIELD, "")?.toLongOrNull() ?: 0L },
                MAX_ENTRIES - 1
            )
        }
        preferences.edit {
            evicted.forEach(::remove)
            putString(key, value.encode() + FIELD + nowMs)
        }
    }

    fun clear() = preferences.edit { clear() }

    companion object {
        private const val PREFERENCES_NAME = "video_picture_adjust"
        private const val KEY_PREFIX = "film|"
        private const val FIELD = '\u001e'
        private const val MAX_ENTRIES = 300

        fun remembers(mediaId: String?): Boolean =
            mediaId != null && !VaultVideos.isVault(mediaId) &&
                !mediaId.startsWith("${ContentResolver.SCHEME_CONTENT}:") && !Settings.VIDEO_NO_HISTORY.valueCompat
    }
}
