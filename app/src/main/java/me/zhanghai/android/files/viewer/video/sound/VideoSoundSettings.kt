/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.sound

import android.content.Context
import me.zhanghai.android.files.player.AudioSettings
import me.zhanghai.android.files.player.AudioSettingsCodec
import me.zhanghai.android.files.viewer.video.VideoResumeStore
import org.json.JSONObject

/**
 * Ordo Video's own sound, apart from Ordo Musik's: the Klang part (equalizer, bass, balance, mono) reuses
 * [AudioSettings] and its codec, the video-only parts sit beside it. The A/V delay belongs to the output device and
 * has its own store ([VideoDelayStore]).
 */
data class VideoSoundSettings(
    val klang: AudioSettings = AudioSettings(),
    val dialogLevel: Int = VideoSoundMath.DIALOG_OFF,
    val nightMode: Boolean = false,
    /** "Dialog freistellen plus" (DeepFilterNet3, [DialogIsolation]); works once its model is downloaded. */
    val isolateDialog: Boolean = false
)

internal object VideoSoundCodec {
    fun encode(value: VideoSoundSettings): String = JSONObject()
        .put("klang", AudioSettingsCodec.encode(value.klang))
        .put("dialog", value.dialogLevel)
        .put("night", value.nightMode)
        .put("isolate", value.isolateDialog)
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
                nightMode = json.optBoolean("night", false),
                isolateDialog = json.optBoolean("isolate", false)
            )
        } catch (_: Exception) {
            VideoSoundSettings()
        }
    }
}

internal object VideoSoundStore {
    const val NAME = "video_sound"
    private const val KEY = "settings"

    fun read(context: Context): VideoSoundSettings =
        VideoSoundCodec.decode(context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY, null))

    fun write(context: Context, value: VideoSoundSettings) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY, VideoSoundCodec.encode(value)).apply()
    }
}

/**
 * The A/V delay per output device ([VideoDeviceDelay.key]). At most [VideoResumeStore.MAX_ENTRIES], the oldest go
 * first; 0 ms removes the entry.
 */
internal class VideoDelayStore(context: Context) {
    private val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun read(key: String): Int? = decode(preferences.getString(key, null))?.first

    fun write(key: String, delayMs: Int, nowMs: Long = System.currentTimeMillis()) {
        val editor = preferences.edit()
        if (delayMs == 0) {
            editor.remove(key)
        } else {
            if (!preferences.contains(key)) {
                VideoResumeStore.keysToEvict(
                    preferences.all.mapValues { (it.value as? String)?.let(::decode)?.second ?: 0L },
                    VideoResumeStore.MAX_ENTRIES - 1
                ).forEach { editor.remove(it) }
            }
            editor.putString(key, encode(delayMs, nowMs))
        }
        editor.apply()
    }

    companion object {
        private const val NAME = "video_av_delay_device"
        // 0.17.0 kept the delay per film, keyed by the film's path; never read again, so deleted once.
        private const val LEGACY_NAME = "video_av_delay"
        private const val LEGACY_DROPPED = "legacy_av_delay_dropped"

        /** Deletes 0.17.0's per-film delays (film paths are private) once; off the main thread. */
        fun dropLegacy(context: Context) {
            val flags = context.getSharedPreferences(VideoSoundStore.NAME, Context.MODE_PRIVATE)
            dropLegacyOnce(
                { flags.getBoolean(LEGACY_DROPPED, false) }, { context.deleteSharedPreferences(LEGACY_NAME) },
                { flags.edit().putBoolean(LEGACY_DROPPED, true).apply() }
            )
        }

        internal fun dropLegacyOnce(done: () -> Boolean, delete: () -> Unit, markDone: () -> Unit) {
            if (done()) return
            delete()
            markDone()
        }

        internal fun encode(delayMs: Int, savedAtMs: Long): String = "$delayMs:$savedAtMs"

        /** Delay (within ±[VideoSoundMath.DELAY_MAX_MS], snapped) and save time, or null for anything unreadable. */
        internal fun decode(value: String?): Pair<Int, Long>? {
            val parts = value?.split(':')?.takeIf { it.size == 2 } ?: return null
            val delay = parts[0].toIntOrNull() ?: return null
            val savedAt = parts[1].toLongOrNull() ?: return null
            return VideoSoundMath.stepDelay(delay, 0) to savedAt
        }
    }
}
