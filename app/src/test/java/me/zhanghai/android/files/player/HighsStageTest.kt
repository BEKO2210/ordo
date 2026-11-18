/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** "Fill in missing highs": it finds an encoder's cut, adds only above it, and does nothing to full-band music. */
class HighsStageTest {
    private val rate = 44_100

    /** Sum of many sines with random phases from 100 Hz up to [topHz]: flat up to there, nothing above. */
    private fun music(topHz: Int, seconds: Double): FloatArray {
        val random = Random(7)
        val frequencies = (1..240).map { 100.0 + (topHz - 100.0) * it / 240 }
        val phases = frequencies.map { random.nextDouble() * 2 * PI }
        return FloatArray((rate * seconds).toInt()) { n ->
            var sum = 0.0
            for (i in frequencies.indices) sum += sin(2 * PI * frequencies[i] * n / rate + phases[i])
            (sum / frequencies.size * 0.8).toFloat()
        }
    }

    /** Power above [hz] in the last 2048 samples. */
    private fun powerAbove(signal: FloatArray, hz: Int): Double {
        val size = CutoffDetector.SIZE
        // Hann window, so the strong tones just under the cut do not leak above it.
        val real = DoubleArray(size) {
            signal[signal.size - size + it] * (0.5 - 0.5 * kotlin.math.cos(2 * PI * it / size))
        }
        val imag = DoubleArray(CutoffDetector.SIZE)
        CutoffDetector.fft(real, imag)
        val first = hz * CutoffDetector.SIZE / rate
        return (first until CutoffDetector.SIZE / 2).sumOf { real[it] * real[it] + imag[it] * imag[it] }
    }

    private fun run(stage: HighsStage, input: FloatArray): FloatArray {
        stage.effectEnabled = true
        stage.flush(rate)
        val output = FloatArray(input.size)
        var index = 0
        while (index < input.size) {
            stage.begin()
            val end = minOf(input.size, index + 512)
            for (n in index until end) {
                stage.process(input[n], input[n])
                output[n] = stage.outLeft
            }
            index = end
        }
        return output
    }

    @Test
    fun findsTheCutOfA128KbitFile() {
        val stage = HighsStage()
        run(stage, music(16_000, 3.0))
        assertTrue("cut ${stage.detectedCutoffHz}", stage.detectedCutoffHz in 15_500..16_500)
    }

    @Test
    fun fullBandMusicHasNoCutAndStaysUntouched() {
        val stage = HighsStage().apply { level = 2 }
        val input = music(21_000, 3.0)
        val output = run(stage, input)
        assertEquals(0, stage.detectedCutoffHz)
        for (n in input.indices) assertEquals(input[n], output[n], 0f)
    }

    @Test
    fun addsQuietHighsOnlyAboveTheCut() {
        val stage = HighsStage().apply { level = 2 }
        val input = music(16_000, 6.0)
        val output = run(stage, input)
        val added = powerAbove(output, 16_500)
        val before = powerAbove(input, 16_500)
        assertTrue("something is added above the cut: $added vs $before", added > before * 100 + 1e-3)
        // Never more than -24 dB of what lies below the cut.
        val below = powerAbove(output, 1_000) - powerAbove(output, 16_000)
        assertTrue("added ${10 * kotlin.math.log10(added / below)} dB", added < below * 0.004)
        // Below the cut the music is the same within a whisker.
        val tail = input.size - CutoffDetector.SIZE
        var difference = 0.0
        var energy = 0.0
        for (n in tail until input.size) {
            difference += (output[n] - input[n]).toDouble().let { it * it }
            energy += input[n].toDouble() * input[n]
        }
        assertTrue("change ${10 * kotlin.math.log10(difference / energy)} dB", difference < energy * 0.004)
    }
}
