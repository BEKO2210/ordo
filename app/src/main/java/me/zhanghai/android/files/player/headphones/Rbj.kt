/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.headphones

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Peaking and shelving biquads after Robert Bristow-Johnson's Audio EQ Cookbook (the Q form of the shelves, as AutoEq
 * writes them), normalised so a0 = 1: coefficients are b0, b1, b2, a1, a2.
 * https://www.w3.org/TR/audio-eq-cookbook/
 */
internal object Rbj {
    /** Filter types, the same codes as autoeq_presets.json. */
    const val PEAKING = 0
    const val LOW_SHELF = 1
    const val HIGH_SHELF = 2

    fun coefficients(type: Int, frequencyHz: Double, gainDb: Double, q: Double, sampleRate: Int, out: DoubleArray) {
        val a = 10.0.pow(gainDb / 40)
        // Above 0.45 fs the bilinear warp folds the filter; AutoEq's top filters sit near 10-16 kHz.
        val w0 = 2 * PI * frequencyHz.coerceAtMost(sampleRate * 0.45) / sampleRate
        val alpha = sin(w0) / (2 * q)
        val c = cos(w0)
        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double
        when (type) {
            LOW_SHELF -> {
                val s = 2 * sqrt(a) * alpha
                b0 = a * ((a + 1) - (a - 1) * c + s)
                b1 = 2 * a * ((a - 1) - (a + 1) * c)
                b2 = a * ((a + 1) - (a - 1) * c - s)
                a0 = (a + 1) + (a - 1) * c + s
                a1 = -2 * ((a - 1) + (a + 1) * c)
                a2 = (a + 1) + (a - 1) * c - s
            }
            HIGH_SHELF -> {
                val s = 2 * sqrt(a) * alpha
                b0 = a * ((a + 1) + (a - 1) * c + s)
                b1 = -2 * a * ((a - 1) + (a + 1) * c)
                b2 = a * ((a + 1) + (a - 1) * c - s)
                a0 = (a + 1) - (a - 1) * c + s
                a1 = 2 * ((a - 1) - (a + 1) * c)
                a2 = (a + 1) - (a - 1) * c - s
            }
            else -> {
                b0 = 1 + alpha * a
                b1 = -2 * c
                b2 = 1 - alpha * a
                a0 = 1 + alpha / a
                a1 = -2 * c
                a2 = 1 - alpha / a
            }
        }
        out[0] = b0 / a0
        out[1] = b1 / a0
        out[2] = b2 / a0
        out[3] = a1 / a0
        out[4] = a2 / a0
    }

    /** |H| in dB at [frequencyHz]. */
    fun magnitudeDb(c: DoubleArray, frequencyHz: Double, sampleRate: Int): Double {
        val w = 2 * PI * frequencyHz / sampleRate
        val nr = c[0] + c[1] * cos(w) + c[2] * cos(2 * w)
        val ni = -(c[1] * sin(w) + c[2] * sin(2 * w))
        val dr = 1 + c[3] * cos(w) + c[4] * cos(2 * w)
        val di = -(c[3] * sin(w) + c[4] * sin(2 * w))
        return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }
}

/** One biquad on a stereo signal, direct form I in doubles (low shelves at 30 Hz need the precision). */
internal class StereoBiquad {
    val c = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)
    private val state = DoubleArray(8)

    fun clear() = state.fill(0.0)

    fun left(x: Float): Float = run(x.toDouble(), 0).toFloat()

    fun right(x: Float): Float = run(x.toDouble(), 4).toFloat()

    private fun run(x: Double, o: Int): Double {
        val y = c[0] * x + c[1] * state[o] + c[2] * state[o + 1] - c[3] * state[o + 2] - c[4] * state[o + 3]
        state[o + 1] = state[o]
        state[o] = x
        state[o + 3] = state[o + 2]
        // Denormals cost hundreds of cycles each on some cores; a decaying tail is silence long before them.
        state[o + 2] = if (y > -1e-30 && y < 1e-30) 0.0 else y
        return y
    }
}
