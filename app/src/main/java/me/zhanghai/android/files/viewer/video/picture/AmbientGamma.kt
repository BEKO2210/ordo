/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10

/**
 * Gamma for a bright room after ITU-R BT.2390-12 (03/2025), § on non-reference viewing environments (BBC test,
 * 21 viewers): γ_bright = γ_ref − 0.076 · log10(L_amb / 5), for surrounds above 5 cd/m²; at or below 5 the reference
 * gamma stays. γ_ref is the end-to-end system gamma, 1.2 (BT.2100 HLG at 1000 cd/m², also roughly what SDR's
 * BT.709 camera curve and BT.1886 display give together).
 *
 * The light sensor gives illuminance in lux, the formula wants the surround's luminance. Assumption, to be
 * calibrated on devices: a matte room of 20 % average reflectance, L = E · ρ / π.
 *
 * The shader applies the change as a ratio on the gamma-encoded luma, like HLG's OOTF does on linear luminance:
 * rgb · Y'^(r − 1) with r = γ_bright / γ_ref; r < 1 lifts the mid-tones that a bright room swallows.
 */
object AmbientGamma {
    const val REFERENCE_GAMMA = 1.2
    const val REFERENCE_SURROUND = 5.0
    const val REFLECTANCE = 0.2
    /** At most 0.01 of gamma per second (no visible pumping, WISSENSCHAFT.md §1.2.5). */
    const val MAX_GAMMA_PER_SECOND = 0.01
    /** Low-pass time constant of 5 s. */
    const val TIME_CONSTANT_S = 5.0

    fun surroundLuminance(lux: Float): Double = lux.coerceAtLeast(0f) * REFLECTANCE / PI

    fun gamma(surroundLuminance: Double): Double =
        if (surroundLuminance <= REFERENCE_SURROUND) {
            REFERENCE_GAMMA
        } else {
            REFERENCE_GAMMA - 0.076 * log10(surroundLuminance / REFERENCE_SURROUND)
        }

    /** One smoothing step of [dtSeconds] from [current] towards [target]: low-pass, then the rate limit. */
    fun step(current: Double, target: Double, dtSeconds: Double): Double {
        val lowPassed = current + (target - current) * (1 - exp(-dtSeconds / TIME_CONSTANT_S))
        val limit = MAX_GAMMA_PER_SECOND * dtSeconds
        return lowPassed.coerceIn(current - limit, current + limit)
    }

    /** The exponent ratio the shader gets. */
    fun ratio(gamma: Double): Float = (gamma / REFERENCE_GAMMA).toFloat()
}
