/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import androidx.core.os.bundleOf
import androidx.media3.session.SessionCommand

object AudioPlayerCommands {
    const val ACTION_APPLY_SETTINGS = "me.zhanghai.android.files.player.APPLY_SETTINGS"
    const val ACTION_GET_SOUND_STATUS = "me.zhanghai.android.files.player.GET_SOUND_STATUS"
    const val ACTION_SLEEP_TIMER = "me.zhanghai.android.files.player.SLEEP_TIMER"
    const val EXTRA_SLEEP_MINUTES = "sleepMinutes"
    const val EXTRA_END_OF_TRACK = "endOfTrack"
    const val EXTRA_EQ_AVAILABLE = "equalizerAvailable"
    const val EXTRA_DP = "dynamicsProcessing"
    const val EXTRA_INPUT_GAIN_AVAILABLE = "inputGainAvailable"
    const val EXTRA_LEGACY_BANDS = "legacyBands"
    const val EXTRA_BASS_AVAILABLE = "bassBoostAvailable"
    const val EXTRA_CLARITY_AVAILABLE = "clarityAvailable"
    const val EXTRA_HEADPHONES = "headphones"
    const val EXTRA_HIGHS_CUTOFF = "highsCutoff"
    const val EXTRA_VOLUME_FRACTION = "volumeFraction"
    const val EXTRA_SLEEP_ACTIVE = "sleepActive"
    const val EXTRA_SLEEP_REMAINING_MS = "sleepRemainingMs"
    /** Session extra: the output badge ("Bit-perfekt · 24/96", "Offload") or null. */
    const val EXTRA_OUTPUT = "output"
    /** Bit-perfect is on and a USB DAC plays, but not this song (rate, channels or bit depth). */
    const val EXTRA_SONG_NOT_BIT_PERFECT = "songNotBitPerfect"

    val APPLY_SETTINGS = SessionCommand(ACTION_APPLY_SETTINGS, bundleOf())
    val GET_SOUND_STATUS = SessionCommand(ACTION_GET_SOUND_STATUS, bundleOf())
    val SLEEP_TIMER = SessionCommand(ACTION_SLEEP_TIMER, bundleOf())
}
