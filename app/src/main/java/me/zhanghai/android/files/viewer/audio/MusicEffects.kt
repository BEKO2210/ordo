/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.content.Context
import android.os.PowerManager
import androidx.core.content.getSystemService
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.util.valueCompat

/**
 * Ordo Musik › Settings › "Visual effects". Maximal (default): the backdrop flows at 30 frames a second, the wave at
 * 30. Balanced: the backdrop steps with the time once a second, the wave flows at 10. Saver: both stand still.
 * Battery saver and reduced motion (Ordo's or the system's) always mean Saver; a device that cannot keep Maximal's
 * frames falls back to Balanced for the rest of the session ([degraded]).
 */
object MusicEffects {
    enum class Level { MAXIMAL, BALANCED, SAVER }

    /** Set once Maximal's backdrop frames came out at less than half their rate. */
    @Volatile
    var degraded = false

    fun level(context: Context): Level {
        if (!Motion.full || context.getSystemService<PowerManager>()?.isPowerSaveMode == true) return Level.SAVER
        return when (Settings.MUSIC_EFFECTS.valueCompat) {
            "saver" -> Level.SAVER
            "balanced" -> Level.BALANCED
            else -> if (degraded) Level.BALANCED else Level.MAXIMAL
        }
    }

    /** The wave's phase step: 30 or 10 frames a second; 0 = it does not flow. */
    fun waveFrameMs(level: Level): Long = when (level) {
        Level.MAXIMAL -> 33L
        Level.BALANCED -> 99L
        Level.SAVER -> 0L
    }

    /** The backdrop's own frame period; 0 = it steps with the player's time update once a second (or stands). */
    fun backdropFrameMs(level: Level): Long = if (level == Level.MAXIMAL) 33L else 0L
}
