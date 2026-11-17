/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import android.content.Context
import androidx.core.content.edit
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject

/**
 * The queue, the position and the modes are separate entries so that the frequent writes (position every few
 * seconds) never re-encode the queue. Only Ordo's own paths are kept, never `content://` items of an external launch.
 */
class PlaybackStateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): State? {
        val paths = decodeQueue(preferences.getString(KEY_QUEUE, null)) ?: return null
        val (index, position) = decodePosition(preferences.getString(KEY_POSITION, null))
        val modes = readModes()
        return State(paths, index.coerceIn(paths.indices), position, modes.shuffleEnabled, modes.repeatMode)
    }

    fun readModes(): Modes = decodeModes(preferences.getString(KEY_MODES, null))

    fun writeQueue(paths: List<String>) {
        preferences.edit { putString(KEY_QUEUE, encodeQueue(paths)) }
    }

    fun writePosition(index: Int, positionMs: Long) {
        preferences.edit { putString(KEY_POSITION, encodePosition(index, positionMs)) }
    }

    fun writeModes(modes: Modes) {
        preferences.edit { putString(KEY_MODES, encodeModes(modes)) }
    }

    /** Forgets the queue and the position; the modes are a preference and stay. */
    fun clearQueue() {
        preferences.edit {
            remove(KEY_QUEUE)
            remove(KEY_POSITION)
        }
    }

    data class Modes(
        val shuffleEnabled: Boolean = false,
        val repeatMode: Int = Player.REPEAT_MODE_OFF
    )

    data class State(
        val paths: List<String>,
        val currentIndex: Int,
        val positionMs: Long,
        val shuffleEnabled: Boolean = false,
        val repeatMode: Int = Player.REPEAT_MODE_OFF
    )

    companion object {
        private const val PREFERENCES_NAME = "audio_playback_state"
        private const val KEY_QUEUE = "queue"
        private const val KEY_POSITION = "position"
        private const val KEY_MODES = "modes"

        internal fun encodeQueue(paths: List<String>): String = JSONArray(paths).toString()

        internal fun decodeQueue(value: String?): List<String>? {
            if (value.isNullOrBlank()) return null
            return try {
                val json = JSONArray(value)
                buildList(json.length()) {
                    for (index in 0 until json.length()) {
                        json.optString(index).takeIf(String::isNotBlank)?.let(::add)
                    }
                }.takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
        }

        internal fun encodePosition(index: Int, positionMs: Long): String =
            JSONObject().put("index", index).put("position", positionMs).toString()

        internal fun decodePosition(value: String?): Pair<Int, Long> {
            if (value.isNullOrBlank()) return 0 to 0L
            return try {
                val json = JSONObject(value)
                json.optInt("index", 0).coerceAtLeast(0) to json.optLong("position", 0L).coerceAtLeast(0L)
            } catch (_: Exception) {
                0 to 0L
            }
        }

        internal fun encodeModes(modes: Modes): String =
            JSONObject().put("shuffle", modes.shuffleEnabled).put("repeat", modes.repeatMode).toString()

        internal fun decodeModes(value: String?): Modes {
            if (value.isNullOrBlank()) return Modes()
            return try {
                val json = JSONObject(value)
                Modes(
                    json.optBoolean("shuffle", false),
                    json.optInt("repeat", Player.REPEAT_MODE_OFF)
                        .takeIf { it in Player.REPEAT_MODE_OFF..Player.REPEAT_MODE_ALL }
                        ?: Player.REPEAT_MODE_OFF
                )
            } catch (_: Exception) {
                Modes()
            }
        }
    }
}
