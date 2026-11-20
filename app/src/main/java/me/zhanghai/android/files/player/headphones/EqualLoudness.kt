/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.headphones

import kotlin.math.log10
import kotlin.math.pow

/**
 * "Full sound when quiet" after the equal-loudness contours of ISO 226:2003 (Table 1 and formula 1; the 2023 edition
 * could not be read, iso.org is closed to us). The same parameters are published with the derivation in Suzuki and
 * Takeshima, JASA 116(2), 2004, doi:10.1121/1.1763601.
 *
 * At a lower level the ear loses the lows (and a little of the very highs) faster than the mids. The correction for a
 * listening level L is how much more the L-phon contour rises against 1 kHz than the [REFERENCE_PHON] contour does.
 * Ordo follows it with one low shelf and one high shelf, fitted by least squares from 31.5 Hz to 12.5 kHz, and capped
 * at [MAX_BASS_DB] / [MAX_TREBLE_DB] so it stays gentle.
 */
object EqualLoudness {
    /** ISO 226:2003 Table 1: frequency (Hz), exponent αf, magnitude Lu (dB), threshold Tf (dB). */
    val FREQUENCIES_HZ = doubleArrayOf(
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0,
        800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0
    )
    private val ALPHA = doubleArrayOf(
        0.532, 0.506, 0.480, 0.455, 0.432, 0.409, 0.387, 0.367, 0.349, 0.330, 0.315, 0.301, 0.288, 0.276, 0.267,
        0.259, 0.253, 0.250, 0.246, 0.244, 0.243, 0.243, 0.243, 0.242, 0.242, 0.245, 0.254, 0.271, 0.301
    )
    private val LU = doubleArrayOf(
        -31.6, -27.2, -23.0, -19.1, -15.9, -13.0, -10.3, -8.1, -6.2, -4.5, -3.1, -2.0, -1.1, -0.4, 0.0, 0.3, 0.5,
        0.0, -2.7, -4.1, -1.0, 1.7, 2.5, 1.2, -2.1, -7.1, -11.2, -10.7, -3.1
    )
    private val TF = doubleArrayOf(
        78.5, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9, 14.4, 11.4, 8.6, 6.2, 4.4, 3.0, 2.2, 2.4, 3.5,
        1.7, -1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6, 13.9, 12.3
    )

    /** The level music is mixed and judged at; at or above it nothing is added. */
    const val REFERENCE_PHON = 80.0
    const val MAX_BASS_DB = 10.0
    const val MAX_TREBLE_DB = 6.0
    const val LOW_SHELF_HZ = 125.0
    const val LOW_SHELF_Q = 0.5
    const val HIGH_SHELF_HZ = 12_000.0
    const val HIGH_SHELF_Q = 0.707

    /**
     * Assumed sound pressure of a full-scale tone at full system volume, in dB SPL. An assumption, not a measurement:
     * phones and headphones reach roughly 100 dB at full volume, which is what EN 50332 limits portable players to
     * (standard not read). The calibration chips move it by ±10 dB.
     */
    const val FULL_VOLUME_SPL = 100.0

    /** Sound pressure level (dB) of a tone at table frequency [index] that sounds as loud as [phon] (ISO 226 formula 1). */
    fun splDb(index: Int, phon: Double): Double {
        val af = 4.47e-3 * (10.0.pow(0.025 * phon) - 1.15) +
            (0.4 * 10.0.pow((TF[index] + LU[index]) / 10 - 9)).pow(ALPHA[index])
        return 10 / ALPHA[index] * log10(af) - LU[index] + 94
    }

    /** The lift per table frequency that makes [phon] sound like the reference (0 at 1 kHz, 0 at the reference). */
    fun correctionDb(phon: Double): DoubleArray {
        val level = phon.coerceAtMost(REFERENCE_PHON)
        return DoubleArray(FREQUENCIES_HZ.size) { (splDb(it, level) - level) - (splDb(it, REFERENCE_PHON) - REFERENCE_PHON) }
    }

    /** Estimated listening level in phon (≈ dB SPL at 1 kHz) at [attenuationDb] below full volume. */
    fun listeningPhon(attenuationDb: Float, calibrationDb: Int): Double =
        FULL_VOLUME_SPL + attenuationDb + calibrationDb

    /**
     * Low-shelf and high-shelf gain (dB) for [phon]: least squares of the two shelf shapes against [correctionDb] from
     * 31.5 Hz to 12.5 kHz (the shelves' dB response is close to proportional to their gain), then capped.
     */
    fun shelfGainsDb(phon: Double): DoubleArray {
        if (phon >= REFERENCE_PHON) return DoubleArray(2)
        val target = correctionDb(phon)
        var s11 = 0.0
        var s12 = 0.0
        var s22 = 0.0
        var t1 = 0.0
        var t2 = 0.0
        for (i in FIT_FROM until FREQUENCIES_HZ.size) {
            val u = BASIS[0][i]
            val v = BASIS[1][i]
            s11 += u * u
            s12 += u * v
            s22 += v * v
            t1 += u * target[i]
            t2 += v * target[i]
        }
        val det = s11 * s22 - s12 * s12
        val bass = (t1 * s22 - t2 * s12) / det
        val treble = (t2 * s11 - t1 * s12) / det
        return doubleArrayOf(bass.coerceIn(0.0, MAX_BASS_DB), treble.coerceIn(0.0, MAX_TREBLE_DB))
    }

    /** Both shelves' response at the table frequencies (48 kHz), for gains [bassDb] and [trebleDb]. */
    fun responseDb(bassDb: Double, trebleDb: Double, sampleRate: Int = FIT_RATE): DoubleArray {
        val low = DoubleArray(5)
        val high = DoubleArray(5)
        Rbj.coefficients(Rbj.LOW_SHELF, LOW_SHELF_HZ, bassDb, LOW_SHELF_Q, sampleRate, low)
        Rbj.coefficients(Rbj.HIGH_SHELF, HIGH_SHELF_HZ, trebleDb, HIGH_SHELF_Q, sampleRate, high)
        return DoubleArray(FREQUENCIES_HZ.size) {
            Rbj.magnitudeDb(low, FREQUENCIES_HZ[it], sampleRate) + Rbj.magnitudeDb(high, FREQUENCIES_HZ[it], sampleRate)
        }
    }

    private const val FIT_RATE = 48_000
    /** 31.5 Hz: below it headphones and phones hardly play anything that a lift could restore. */
    private const val FIT_FROM = 2

    /** Each shelf's response at +1 dB. */
    private val BASIS: Array<DoubleArray> by lazy { arrayOf(responseDb(1.0, 0.0), responseDb(0.0, 1.0)) }

    /**
     * Android's reference media volume curve (AOSP default_volume_tables.xml, DEFAULT_MEDIA_VOLUME_CURVE: 1 → −58 dB,
     * 20 → −40 dB, 60 → −17 dB, 100 → 0 dB), for devices below API 28 where AudioManager.getStreamVolumeDb is missing.
     */
    fun defaultCurveDb(index: Int, minIndex: Int, maxIndex: Int): Float {
        if (maxIndex <= minIndex) return 0f
        val x = 100f * (index - minIndex) / (maxIndex - minIndex)
        val points = DEFAULT_CURVE
        if (x <= points[0]) return points[1]
        var i = 2
        while (i < points.size - 2 && x > points[i]) i += 2
        val x0 = points[i - 2]
        val y0 = points[i - 1]
        return y0 + (points[i + 1] - y0) * (x - x0) / (points[i] - x0)
    }

    private val DEFAULT_CURVE = floatArrayOf(1f, -58f, 20f, -40f, 60f, -17f, 100f, 0f)
}
