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

class PlaybackStateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): State? = decode(preferences.getString(KEY_STATE, null))

    fun write(state: State) {
        preferences.edit { putString(KEY_STATE, encode(state)) }
    }

    data class State(
        val paths: List<String>,
        val currentIndex: Int,
        val positionMs: Long,
        val shuffleEnabled: Boolean = false,
        val repeatMode: Int = Player.REPEAT_MODE_OFF
    )

    companion object {
        private const val PREFERENCES_NAME = "audio_playback_state"
        private const val KEY_STATE = "state"

        internal fun encode(state: State): String = JSONObject().apply {
            put("paths", JSONArray(state.paths))
            put("index", state.currentIndex)
            put("position", state.positionMs)
            put("shuffle", state.shuffleEnabled)
            put("repeat", state.repeatMode)
        }.toString()

        internal fun decode(value: String?): State? {
            if (value.isNullOrBlank()) return null
            return try {
                val json = JSONObject(value)
                val pathsJson = json.getJSONArray("paths")
                val paths = buildList(pathsJson.length()) {
                    for (index in 0 until pathsJson.length()) {
                        pathsJson.optString(index).takeIf(String::isNotBlank)?.let(::add)
                    }
                }
                if (paths.isEmpty()) return null
                State(
                    paths,
                    json.optInt("index", 0).coerceIn(paths.indices),
                    json.optLong("position", 0L).coerceAtLeast(0L),
                    json.optBoolean("shuffle", false),
                    json.optInt("repeat", Player.REPEAT_MODE_OFF)
                        .takeIf { it in Player.REPEAT_MODE_OFF..Player.REPEAT_MODE_ALL }
                        ?: Player.REPEAT_MODE_OFF
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
