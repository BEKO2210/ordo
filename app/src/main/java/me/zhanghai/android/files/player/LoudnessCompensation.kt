/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

/**
 * "Full sound when quiet": at low volume the ear loses the lows first and a little of the very highs (the curves of
 * equal loudness, ISO 226:2023, flatten as the level rises). Ordo lifts the outer equalizer bands gently as the system
 * volume goes down, nothing at or above [REFERENCE] of full volume, at most +6 dB at 31 and 62 Hz near silence.
 *
 * Only an approximation, and said so: the real sound pressure depends on the headphones or speaker, which Ordo cannot
 * know. The shape follows how much the 40-phon curve of ISO 226 rises against the 80-phon one at the band frequencies
 * (relative to 1 kHz), scaled down to a gentle maximum (docs/AUDIO-QUALITAET-RECHERCHE.md, 5).
 */
object LoudnessCompensation {
    /** Volume fraction (of the maximum index) from which on nothing is added. */
    const val REFERENCE = 0.8f

    /** Most lift per band (dB) at the lowest volume: 31, 62, 125, 250, 500 Hz, 1, 2, 4, 8, 16 kHz. */
    private val MAX_LIFT_DB = floatArrayOf(6f, 6f, 4f, 2f, 0.5f, 0f, 0f, 0f, 1f, 2f)

    /** 0 at or above [REFERENCE], 1 at silence, linear in between. */
    fun amount(volumeFraction: Float): Float =
        ((REFERENCE - volumeFraction.coerceIn(0f, 1f)) / REFERENCE).coerceIn(0f, 1f)

    /** Lift per equalizer band in dB for this volume. */
    fun liftDb(volumeFraction: Float): FloatArray {
        val amount = amount(volumeFraction)
        return FloatArray(MAX_LIFT_DB.size) { MAX_LIFT_DB[it] * amount }
    }
}
