/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.headphones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class PartitionedConvolverTest {
    @Test
    fun convolutionMatchesDirectReference() {
        for (blockSize in intArrayOf(64, 128)) {
            val random = Random(0x4f52444fL + blockSize)
            val lengths = intArrayOf(256, 100, 1)
            val irs = Array(lengths.size) { channel ->
                Array(2) {
                    FloatArray(lengths[channel]) { ((random.nextDouble() * 2.0 - 1.0) * 0.01).toFloat() }
                }
            }
            val frameCount = 5_000
            val signal = Array(lengths.size) {
                FloatArray(frameCount) { (random.nextDouble() * 2.0 - 1.0).toFloat() }
            }
            val convolver = PartitionedConvolver(irs, blockSize)
            val frame = FloatArray(lengths.size)
            var maximumError = 0.0

            for (outputFrame in 0 until frameCount) {
                for (channel in frame.indices) frame[channel] = signal[channel][outputFrame]
                convolver.processFrame(frame)
                var expectedLeft = 0.0
                var expectedRight = 0.0
                for (channel in signal.indices) {
                    for (tap in irs[channel][0].indices) {
                        val inputFrame = outputFrame - blockSize - tap
                        if (inputFrame >= 0) {
                            expectedLeft += signal[channel][inputFrame].toDouble() * irs[channel][0][tap]
                            expectedRight += signal[channel][inputFrame].toDouble() * irs[channel][1][tap]
                        }
                    }
                }
                maximumError = maxOf(maximumError, abs(convolver.outLeft - expectedLeft))
                maximumError = maxOf(maximumError, abs(convolver.outRight - expectedRight))
            }

            assertTrue("blockSize=$blockSize, maximum error=$maximumError", maximumError < 1e-4)
        }
    }

    @Test
    fun impulseReturnsTheResponse() {
        val blockSize = 64
        val irs = arrayOf(
            arrayOf(floatArrayOf(0.3f), floatArrayOf(-0.2f)),
            arrayOf(
                floatArrayOf(0.4f, -0.25f, 0.125f, 0.0625f, -0.03125f),
                floatArrayOf(-0.1f, 0.2f, 0.3f, -0.4f, 0.5f)
            )
        )
        val convolver = PartitionedConvolver(irs, blockSize)
        val input = FloatArray(2)
        val frames = blockSize + irs[1][0].size + blockSize

        for (frame in 0 until frames) {
            input[1] = if (frame == 0) 1f else 0f
            convolver.processFrame(input)
            val responseIndex = frame - blockSize
            val expectedLeft = if (responseIndex in irs[1][0].indices) irs[1][0][responseIndex] else 0f
            val expectedRight = if (responseIndex in irs[1][1].indices) irs[1][1][responseIndex] else 0f
            assertEquals("left frame $frame", expectedLeft, convolver.outLeft, 1e-5f)
            assertEquals("right frame $frame", expectedRight, convolver.outRight, 1e-5f)
        }
    }

    @Test
    fun resetForgetsHistory() {
        val random = Random(17)
        val irs = Array(3) { Array(2) { FloatArray(173) { random.nextFloat() - 0.5f } } }
        val convolver = PartitionedConvolver(irs, 64)
        val input = FloatArray(3)
        repeat(400) {
            for (channel in input.indices) input[channel] = random.nextFloat() * 2f - 1f
            convolver.processFrame(input)
        }

        convolver.reset()
        input.fill(0f)
        repeat(500) {
            convolver.processFrame(input)
            assertEquals(0f, convolver.outLeft, 0f)
            assertEquals(0f, convolver.outRight, 0f)
        }
    }

    @Test
    fun silenceInSilenceOut() {
        val irs = arrayOf(
            arrayOf(floatArrayOf(1f, -0.5f), floatArrayOf(0.25f, 0.75f)),
            arrayOf(FloatArray(129) { 0.01f }, FloatArray(75) { -0.02f })
        )
        val convolver = PartitionedConvolver(irs, 64)
        val input = FloatArray(2)

        repeat(1_000) {
            convolver.processFrame(input)
            assertEquals(0f, convolver.outLeft, 0f)
            assertEquals(0f, convolver.outRight, 0f)
        }
    }

    @Test
    fun latencyIsTheBlockSize() {
        for (blockSize in intArrayOf(16, 64, 128, 256)) {
            val convolver = PartitionedConvolver(arrayOf(arrayOf(floatArrayOf(1f), floatArrayOf(1f))), blockSize)
            assertEquals(blockSize, convolver.latencyFrames)
        }
    }

    @Test
    fun cpuOfTheConvolver() {
        val sampleRate = 48_000
        val blockSize = 128
        val random = Random(29)
        val irs = Array(6) {
            Array(2) { FloatArray(256) { ((random.nextDouble() * 2.0 - 1.0) / 256.0).toFloat() } }
        }
        val convolver = PartitionedConvolver(irs, blockSize)
        val input = FloatArray(6) { random.nextFloat() * 2f - 1f }

        repeat(sampleRate * 5) { convolver.processFrame(input) }

        val measuredFrames = sampleRate * 60
        val start = System.nanoTime()
        repeat(measuredFrames) { convolver.processFrame(input) }
        val elapsedSeconds = (System.nanoTime() - start) / 1e9
        val realTimeShare = elapsedSeconds / (measuredFrames.toDouble() / sampleRate) * 100.0
        println("PartitionedConvolver: %.2f %% of one core at 48 kHz".format(realTimeShare))
        assertTrue("Real-time share was %.2f %%".format(realTimeShare), realTimeShare < 20.0)
    }
}
