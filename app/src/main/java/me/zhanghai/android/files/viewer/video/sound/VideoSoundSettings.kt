/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.sound

import android.content.Context
import me.zhanghai.android.files.player.AudioSettings
import me.zhanghai.android.files.player.AudioSettingsCodec
import org.json.JSONObject

/**
 * Ordo Video's own sound, apart from Ordo Musik's: the Klang part (equalizer, bass, balance, mono) reuses
 * [AudioSettings] and its codec, the video-only parts sit beside it. The A/V delay is not stored: it belongs to one
 * film, so it lives as long as the player screen.
 */
data class VideoSoundSettings(
    val klang: AudioSettings = AudioSettings(),
    val dialogLevel: Int = VideoSoundMath.DIALOG_OFF,
    val nightMode: Boolean = false
)

internal object VideoSoundCodec {
    fun encode(value: VideoSoundSettings): String = JSONObject()
        .put("klang", AudioSettingsCodec.encode(value.klang))
        .put("dialog", value.dialogLevel)
        .put("night", value.nightMode)
        .toString()

    /** Anything unreadable falls back to the defaults (everything off), never to an error. */
    fun decode(encoded: String?): VideoSoundSettings {
        if (encoded.isNullOrBlank()) return VideoSoundSettings()
        return try {
            val json = JSONObject(encoded)
            VideoSoundSettings(
                klang = AudioSettingsCodec.decode(json.optString("klang")),
                dialogLevel = json.optInt("dialog", VideoSoundMath.DIALOG_OFF)
                    .coerceIn(VideoSoundMath.DIALOG_OFF, VideoSoundMath.DIALOG_STRONG),
                nightMode = json.optBoolean("night", false)
            )
        } catch (_: Exception) {
            VideoSoundSettings()
        }
    }
}

internal object VideoSoundStore {
    private const val NAME = "video_sound"
    private const val KEY = "settings"

    fun read(context: Context): VideoSoundSettings =
        VideoSoundCodec.decode(context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY, null))

    fun write(context: Context, value: VideoSoundSettings) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY, VideoSoundCodec.encode(value)).apply()
    }
}
