/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.background

import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.BooleanSettingLiveData
import me.zhanghai.android.files.settings.SettingLiveData
import me.zhanghai.android.files.settings.StringSettingLiveData

/** Ordo Video's "Keep playing in the background" and "Picture-in-picture" (settings_video.xml, group Playback). */
object VideoBackgroundPrefs {
    const val BACKGROUND_OFF = "off"
    const val BACKGROUND_AUDIO = "audio"

    val BACKGROUND: SettingLiveData<String> =
        StringSettingLiveData(R.string.pref_key_video_background, R.string.pref_default_value_video_background)
    val PIP: SettingLiveData<Boolean> =
        BooleanSettingLiveData(R.string.pref_key_video_pip, R.bool.pref_default_value_true)
}

/** When the video keeps going without its screen, and the picture-in-picture window's shape. Unit-tested. */
object VideoBackgroundRules {
    // Android's limits for a picture-in-picture aspect ratio (PictureInPictureParams.Builder.setAspectRatio).
    private const val MAX_RATIO = 2.39f
    private const val MIN_RATIO = 1f / MAX_RATIO

    /**
     * On leaving the screen: only "Sound only" keeps a playing video going. Closing it, or closing its
     * picture-in-picture window, always pauses.
     */
    fun keepsPlaying(setting: String, playing: Boolean, wasInPip: Boolean, finishing: Boolean): Boolean =
        setting == VideoBackgroundPrefs.BACKGROUND_AUDIO && playing && !wasInPip && !finishing

    /** Home while playing goes to picture-in-picture when the device can and the setting is on. */
    fun entersPip(enabled: Boolean, supported: Boolean, playing: Boolean): Boolean = enabled && supported && playing

    /**
     * Width and height of the window's aspect ratio, from the video's size and pixel shape, clamped to what Android
     * accepts; null while the size is unknown.
     */
    fun pipAspect(width: Int, height: Int, pixelRatio: Float): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        val shown = Math.round(width * (pixelRatio.takeIf { it > 0f && it.isFinite() } ?: 1f)).coerceAtLeast(1)
        val ratio = shown.toFloat() / height
        return when {
            ratio > MAX_RATIO -> 239 to 100
            ratio < MIN_RATIO -> 100 to 239
            else -> shown to height
        }
    }
}
