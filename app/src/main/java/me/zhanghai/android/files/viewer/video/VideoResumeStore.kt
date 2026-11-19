/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

import android.content.Context
import androidx.core.content.edit

/**
 * Where each film was left, one entry per file: the key is path, size and modification time, so a replaced file of
 * the same name starts fresh. At most [MAX_ENTRIES] (the oldest go first); a film barely started or nearly finished
 * (under 10 s, over 95 % or less than 10 s left) is forgotten. Only Ordo's own paths are kept, never `content://`
 * items of another app.
 */
class VideoResumeStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(key: String): Long? = decode(preferences.getString(key, null))?.first

    /** Every saved place, for Ordo Video's "Continue watching". */
    fun readAll(): List<Entry> =
        preferences.all.mapNotNull { (key, value) ->
            val (pathUri, size) = parseKey(key) ?: return@mapNotNull null
            val (position, savedAt) = decode(value as? String) ?: return@mapNotNull null
            Entry(pathUri, size, position, savedAt)
        }

    /** "Clear Continue watching" in Ordo Video's settings: every saved place goes. */
    fun clear() {
        preferences.edit { clear() }
    }

    data class Entry(val pathUri: String, val size: Long, val positionMs: Long, val savedAtMs: Long)

    fun write(key: String, positionMs: Long, durationMs: Long, nowMs: Long = System.currentTimeMillis()) {
        if (!isWorthKeeping(positionMs, durationMs)) {
            if (preferences.contains(key)) preferences.edit { remove(key) }
            return
        }
        val evicted = if (preferences.contains(key)) {
            emptyList()
        } else {
            keysToEvict(
                preferences.all.mapValues { (it.value as? String)?.let(::decode)?.second ?: 0L }, MAX_ENTRIES - 1
            )
        }
        preferences.edit {
            evicted.forEach(::remove)
            putString(key, encode(positionMs, nowMs))
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "video_resume"
        internal const val MAX_ENTRIES = 300
        internal const val MIN_POSITION_MS = 10_000L
        internal const val MIN_LEFT_MS = 10_000L
        internal const val MAX_FRACTION = 0.95

        fun key(pathUri: String, size: Long, lastModifiedMs: Long): String = "$pathUri|$size|$lastModifiedMs"

        /** Path URI and size back from a [key]; null for anything else. */
        internal fun parseKey(key: String): Pair<String, Long>? {
            val withoutTime = key.substringBeforeLast('|', "")
            val size = withoutTime.substringAfterLast('|').toLongOrNull() ?: return null
            val pathUri = withoutTime.substringBeforeLast('|', "").takeIf { it.isNotEmpty() } ?: return null
            return pathUri to size
        }

        /** Started (10 s) and not finished: less than 95 % seen and at least 10 s left. */
        fun isWorthKeeping(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0 && positionMs >= MIN_POSITION_MS && positionMs <= durationMs * MAX_FRACTION &&
                durationMs - positionMs >= MIN_LEFT_MS

        /** The oldest keys beyond [keep], by the time they were saved. */
        internal fun keysToEvict(savedAtByKey: Map<String, Long>, keep: Int): List<String> =
            savedAtByKey.entries.sortedBy { it.value }
                .take((savedAtByKey.size - keep).coerceAtLeast(0))
                .map { it.key }

        internal fun encode(positionMs: Long, savedAtMs: Long): String = "$positionMs:$savedAtMs"

        /** Position and save time, or null for anything unreadable. */
        internal fun decode(value: String?): Pair<Long, Long>? {
            val parts = value?.split(':') ?: return null
            if (parts.size != 2) return null
            val position = parts[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val savedAt = parts[1].toLongOrNull() ?: return null
            return position to savedAt
        }
    }
}
