/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** BS.1770 loudness, checked against the standard's own numbers. */
class LoudnessMeterTest {
    private fun measure(rate: Int, seconds: Double, left: (Int) -> Double, right: (Int) -> Double): LoudnessMeter {
        val meter = LoudnessMeter(rate, 2)
        val frame = FloatArray(2)
        for (n in 0 until (rate * seconds).toInt()) {
            frame[0] = left(n).toFloat()
            frame[1] = right(n).toFloat()
            meter.add(frame)
        }
        return meter
    }

    private fun sine(rate: Int, dbfs: Double): (Int) -> Double {
        val amplitude = 10.0.pow(dbfs / 20)
        return { n -> amplitude * sin(2 * PI * 997 * n / rate) }
    }

    @Test
    fun theKWeightingMatchesTheStandardAt48Khz() {
        // ITU-R BS.1770, Annex 1, tables 1 and 2.
        assertArrayEquals(
            doubleArrayOf(1.53512485958697, -2.69169618940638, 1.19839281085285, -1.69065929318241, 0.73248077421585),
            LoudnessMeter.shelfCoefficients(48_000), 1e-9
        )
        assertArrayEquals(
            doubleArrayOf(1.0, -2.0, 1.0, -1.99004745483398, 0.99007225036621),
            LoudnessMeter.highPassCoefficients(48_000), 1e-9
        )
    }

    @Test
    fun aFullScaleSineInOneChannelReadsMinus3Lkfs() {
        // The standard's calibration: 0 dBFS, about 1 kHz, one channel → -3.01 LKFS.
        for (rate in listOf(44_100, 48_000)) {
            val meter = measure(rate, 5.0, sine(rate, 0.0)) { 0.0 }
            assertEquals(-3.01, meter.integrated()!!, 0.05)
        }
    }

    @Test
    fun stereoMinus23DbfsReadsMinus23Lufs() {
        // EBU Tech 3341, case 1: stereo 1 kHz at -23 dBFS in both channels → -23.0 LUFS.
        val meter = measure(48_000, 20.0, sine(48_000, -23.0), sine(48_000, -23.0))
        assertEquals(-23.0, meter.integrated()!!, 0.1)
    }

    @Test
    fun silenceIsGatedOut() {
        // 10 s of music and 10 s of silence measure like the music alone (absolute gate at -70 LKFS).
        val music = sine(48_000, -20.0)
        val meter = measure(48_000, 20.0, { if (it < 480_000) music(it) else 0.0 }, { if (it < 480_000) music(it) else 0.0 })
        assertEquals(-20.0, meter.integrated()!!, 0.1)
        assertNull(measure(48_000, 2.0, { 0.0 }, { 0.0 }).integrated())
        assertEquals(10.0.pow(-20.0 / 20).toFloat(), meter.peak, 0.001f)
    }
}
