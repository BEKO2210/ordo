/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import kotlin.math.sin

/** "Dialog freistellen plus" without the model: timing (latency compensation), the remix and its fallbacks. */
class DialogIsolationTest {
    private val hop = 480
    private val delay = 1440 // DeepFilterNet3 at 48 kHz: fft 960 − hop 480 + 2 hops lookahead
    private val rate = 48_000

    /** A model that is "perfect" on [speechOf] (speech = that part of the mid) and [delay] samples late, like DF3. */
    private fun delayedModel(speechOf: (Float) -> Float): (FloatArray, FloatArray) -> Boolean {
        val history = ArrayDeque<Float>().apply { repeat(delay) { add(0f) } }
        return { input, output ->
            for (i in input.indices) {
                history.addLast(speechOf(input[i]))
                output[i] = history.removeFirst()
            }
            true
        }
    }

    private fun left(i: Int) = 0.4f * sin(i * 0.013f) + 0.1f * sin(i * 0.31f)
    private fun right(i: Int) = 0.3f * sin(i * 0.017f + 1f) - 0.1f * sin(i * 0.29f)

    private fun run(isolation: DialogIsolation, frames: Int): List<Pair<Float, Float>> {
        val out = mutableListOf<Pair<Float, Float>>()
        isolation.clear()
        for (i in 0 until frames) isolation.push(left(i), right(i)) { l, r -> out += l to r }
        isolation.finish { l, r -> out += l to r }
        return out
    }

    @Test
    fun offPassesTheInputUnchangedFrameForFrame() {
        val isolation = DialogIsolation(hop, delay, rate, delayedModel { it })
        val frames = 5_000 // not a multiple of the hop: the tail must come out at the end
        val out = run(isolation, frames)
        assertEquals(frames, out.size)
        for (i in 0 until frames) {
            assertEquals(left(i), out[i].first, 1e-6f)
            assertEquals(right(i), out[i].second, 1e-6f)
        }
    }

    @Test
    fun theModelDelayIsCompensatedSoSpeechStaysInSync() {
        // All of the mid is "speech": with the delay compensated, the remix gives back the input exactly.
        val isolation = DialogIsolation(hop, delay, rate, delayedModel { it }).apply { enabled = true }
        val frames = 9_600
        val out = run(isolation, frames)
        assertEquals(frames, out.size)
        for (i in 0 until frames) {
            assertEquals(left(i), out[i].first, 1e-5f)
            assertEquals(right(i), out[i].second, 1e-5f)
        }
    }

    @Test
    fun theRestOfTheMidIsLoweredAndTheSideKept() {
        // No speech at all: the mid drops by the rest gain, the side stays; aligned with the input.
        val isolation = DialogIsolation(hop, delay, rate, delayedModel { 0f }).apply { enabled = true }
        val frames = 24_000
        val out = run(isolation, frames)
        val g = DialogIsolation.REST_GAIN
        // After the warm-up (5 hops: the model's delay plus one) and the switch-on glide (0.2 s) the full effect.
        for (i in 5 * hop + (rate * DialogIsolation.GLIDE_SECONDS).toInt() until frames) {
            val mid = (left(i) + right(i)) / 2
            val side = (left(i) - right(i)) / 2
            assertEquals(g * mid + side, out[i].first, 1e-5f)
            assertEquals(g * mid - side, out[i].second, 1e-5f)
        }
    }

    @Test
    fun switchingOnGlidesInsteadOfJumping() {
        val isolation = DialogIsolation(hop, delay, rate, delayedModel { 0f })
        val out = mutableListOf<Pair<Float, Float>>()
        isolation.clear()
        for (i in 0 until 48_000) {
            if (i == 9_600) isolation.enabled = true
            isolation.push(0.5f, 0.5f) { l, r -> out += l to r }
        }
        var largestStep = 0f
        for (i in 1 until out.size) largestStep = maxOf(largestStep, kotlin.math.abs(out[i].first - out[i - 1].first))
        // 0.5 → 0.5·g over 0.2 s: steps of about 0.5·(1 − g) / 9600 per frame, never one jump.
        assertTrue(largestStep < 1e-4f)
        assertEquals(0.5f * DialogIsolation.REST_GAIN, out.last().first, 1e-5f)
    }

    @Test
    fun aFailingModelFallsBackToTheInput() {
        val isolation = DialogIsolation(hop, delay, rate) { _, _ -> false }.apply { enabled = true }
        val frames = 4_800
        val out = run(isolation, frames)
        assertTrue(isolation.failed)
        assertEquals(frames, out.size)
        for (i in 0 until frames) assertEquals(left(i), out[i].first, 1e-6f)
    }

    @Test
    fun aFlushStartsTheDelayAgain() {
        val isolation = DialogIsolation(hop, delay, rate, delayedModel { it })
        run(isolation, 3_000)
        // After clear() the next frames are again frame-exact, without leftovers of the first run.
        val out = run(isolation, 2_000)
        assertEquals(2_000, out.size)
        assertEquals(left(0), out[0].first, 1e-6f)
    }

    @Test
    fun settingIsStoredAndOldSettingsReadAsOff() {
        val on = VideoSoundSettings(isolateDialog = true)
        assertTrue(VideoSoundCodec.decode(VideoSoundCodec.encode(on)).isolateDialog)
        assertFalse(VideoSoundCodec.decode("""{"dialog":1,"night":true}""").isolateDialog)
    }

    @Test
    fun onlyAMeasuredRtfAboveTheLimitHidesTheOption() {
        assertFalse(DialogModel.tooSlow(Float.NaN))
        assertFalse(DialogModel.tooSlow(0.4f))
        assertTrue(DialogModel.tooSlow(0.71f))
    }

    @Test
    fun aFailedHopIsNoMeasurement() {
        var calls = 0
        // First run fails on its third hop, the retry finishes.
        val retried = DialogModel.measureRtf(480, 48_000) { _, _ -> ++calls != 3 }
        assertFalse(retried.isNaN())
        assertFalse(retried.isInfinite())
        val failed = DialogModel.measureRtf(480, 48_000) { _, _ -> false }
        assertTrue(failed.isNaN())
        assertFalse(DialogModel.tooSlow(failed))
    }

    @Test
    fun hashIsLowerCaseHex() {
        val digest = MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", DialogModel.hex(digest))
        assertTrue(DialogModel.SHA256.matches(Regex("[0-9a-f]{64}")))
    }
}
