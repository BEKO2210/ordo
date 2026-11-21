/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.subtitles

import android.content.Context
import androidx.core.content.edit
import me.zhanghai.android.files.viewer.video.VideoResumeStore

/**
 * Per film (Ordo paths only, never `content://` of another app, like [VideoResumeStore]): the subtitle choice, the
 * delay of a subtitle file and a manual charset for one file ([Entry.charsetFile], its content key). At most [MAX_ENTRIES], the oldest go first. Also the look of the
 * subtitles, which holds for every film.
 */
class SubtitleStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * [charset] holds only for the file whose content key is [charsetFile] ([SubtitleFiles.contentKey]): a choice
     * never reaches another file of the film, nor the same name with new content.
     */
    data class Entry(
        val choice: SubtitleChoice?,
        val delayMs: Long = 0,
        val charset: String? = null,
        val charsetFile: String? = null
    ) {
        fun charsetFor(contentKey: String?): String? = charset?.takeIf { contentKey != null && contentKey == charsetFile }
    }

    fun read(mediaId: String): Entry? = decode(preferences.getString(KEY_PREFIX + mediaId, null))

    fun write(mediaId: String, entry: Entry, nowMs: Long = System.currentTimeMillis()) {
        val key = KEY_PREFIX + mediaId
        val evicted = if (preferences.contains(key)) {
            emptyList()
        } else {
            VideoResumeStore.keysToEvict(
                preferences.all.filterKeys { it.startsWith(KEY_PREFIX) }
                    .mapValues { (it.value as? String)?.substringAfterLast(FIELD, "")?.toLongOrNull() ?: 0L },
                MAX_ENTRIES - 1
            )
        }
        preferences.edit {
            evicted.forEach(::remove)
            putString(key, encode(entry, nowMs))
        }
    }

    var style: SubtitleStyle
        get() = SubtitleStyle.decode(preferences.getString(KEY_STYLE, null))
        set(value) = preferences.edit { putString(KEY_STYLE, value.encode()) }

    /** "Clear Continue watching" also forgets what was chosen per film. */
    fun clearChoices() {
        preferences.edit { preferences.all.keys.filter { it.startsWith(KEY_PREFIX) }.forEach(::remove) }
    }

    companion object {
        private const val PREFERENCES_NAME = "video_subtitles"
        private const val KEY_PREFIX = "film|"
        private const val KEY_STYLE = "style"
        private const val FIELD = '\u001e'
        internal const val MAX_ENTRIES = 300

        internal fun encode(entry: Entry, savedAtMs: Long): String =
            listOf(
                entry.choice?.encode().orEmpty(), entry.delayMs, entry.charset.orEmpty(), entry.charsetFile.orEmpty(),
                savedAtMs // last: write() reads the age from there
            ).joinToString(FIELD.toString())

        internal fun decode(value: String?): Entry? {
            val parts = value?.split(FIELD) ?: return null
            // Before 0.17.0 (4 fields) a charset held for every file of the film: a stale choice garbled a UTF-8 file
            // (owner's Tab S9, 20.11.2026, "fÃ¼r"). Such a charset is dropped, the choice and the delay stay.
            if (parts.size !in 4..5) return null
            val charsetFile = if (parts.size == 5) parts[3].ifEmpty { null } else null
            return Entry(
                SubtitleChoice.decode(parts[0].ifEmpty { null }),
                SubtitleTimeline.clampDelay(parts[1].toLongOrNull() ?: 0L),
                parts[2].takeIf { it in SubtitleFiles.CHARSETS && charsetFile != null },
                charsetFile
            )
        }
    }
}
