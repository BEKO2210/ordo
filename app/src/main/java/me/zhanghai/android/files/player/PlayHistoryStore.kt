/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which songs were played, how often and when last: the data behind "Recently played" and "Most played" in Ordo
 * Music. One small JSON entry in Ordo's own preferences, never sent anywhere; only Ordo's own paths, the newest
 * [MAX_ENTRIES] songs.
 */
class PlayHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): List<Entry> = decode(preferences.getString(KEY_HISTORY, null))

    @Synchronized
    fun record(pathUri: String, nowMs: Long) {
        preferences.edit { putString(KEY_HISTORY, encode(record(read(), pathUri, nowMs))) }
    }

    data class Entry(val pathUri: String, val count: Int, val lastPlayedMs: Long)

    companion object {
        private const val PREFERENCES_NAME = "music_play_history"
        private const val KEY_HISTORY = "history"
        internal const val MAX_ENTRIES = 500

        /** One more play of [pathUri]: counted, moved to the front, the oldest entries beyond [maxEntries] dropped. */
        internal fun record(
            entries: List<Entry>,
            pathUri: String,
            nowMs: Long,
            maxEntries: Int = MAX_ENTRIES
        ): List<Entry> {
            val previous = entries.find { it.pathUri == pathUri }
            val updated = Entry(pathUri, (previous?.count ?: 0) + 1, nowMs)
            return (listOf(updated) + entries.filter { it.pathUri != pathUri }).take(maxEntries)
        }

        /** Most recent first. */
        internal fun recent(entries: List<Entry>): List<Entry> = entries.sortedByDescending { it.lastPlayedMs }

        /** Most plays first; equal counts by the latest play. */
        internal fun mostPlayed(entries: List<Entry>): List<Entry> =
            entries.sortedWith(compareByDescending<Entry> { it.count }.thenByDescending { it.lastPlayedMs })

        internal fun encode(entries: List<Entry>): String =
            JSONArray(entries.map {
                JSONObject().put("p", it.pathUri).put("n", it.count).put("t", it.lastPlayedMs)
            }).toString()

        internal fun decode(value: String?): List<Entry> {
            if (value.isNullOrBlank()) return emptyList()
            return try {
                val json = JSONArray(value)
                (0 until json.length()).mapNotNull { index ->
                    val item = json.optJSONObject(index) ?: return@mapNotNull null
                    val path = item.optString("p").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Entry(path, item.optInt("n", 1).coerceAtLeast(1), item.optLong("t", 0L))
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
