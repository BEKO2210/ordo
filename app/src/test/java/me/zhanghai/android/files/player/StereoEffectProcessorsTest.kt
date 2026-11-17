/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Ordo's own effects: crossfeed, stereo width, warmth and reverb, all in 16-bit stereo like real music. */
class StereoEffectProcessorsTest {
    private val stereo = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)

    private fun all(): List<StereoEffectProcessor> = listOf(
        CrossfeedAudioProcessor(), StereoWidthAudioProcessor().apply { widthPercent = 150 },
        WarmthAudioProcessor().apply { level = 3 }, ReverbAudioProcessor().apply { mixPercent = 100 }
    )

    private fun <T : StereoEffectProcessor> T.configured(on: Boolean): T = apply {
        effectEnabled = on
        configure(stereo)
        flush()
    }

    private fun pcm(vararg samples: Short): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
            samples.forEach { putShort(it) }
            flip()
        }

    /** [frames] stereo frames of the same left/right value. */
    private fun steady(left: Short, right: Short, frames: Int): ByteBuffer =
        pcm(*ShortArray(frames * 2) { if (it % 2 == 0) left else right })

    private fun ByteBuffer.shorts(): List<Short> = List(remaining() / 2) { getShort() }

    private fun StereoEffectProcessor.run(input: ByteBuffer): List<Short> {
        queueInput(input)
        return getOutput().shorts()
    }

    // The 0.13.2 lesson: Media3 polls with EMPTY_BUFFER before any data, and copying it into itself throws.
    @Test
    fun emptyInputBeforeAnyDataDoesNotThrow() {
        for (on in listOf(false, true)) {
            all().forEach { processor ->
                processor.configured(on).queueInput(AudioProcessor.EMPTY_BUFFER)
                assertFalse(processor.getOutput().hasRemaining())
            }
        }
    }

    @Test
    fun stereoPassesThroughUnchangedWhenOff() {
        all().forEach { processor ->
            processor.configured(false)
            assertTrue(processor.isActive)
            assertEquals(listOf<Short>(100, -200, 30000, -32768), processor.run(pcm(100, -200, 30000, -32768)))
        }
    }

    @Test
    fun monoAndFloatSourcesBypassEveryEffect() {
        all().forEach { processor ->
            processor.configure(AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT))
            assertFalse(processor.isActive)
            processor.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT))
            assertFalse(processor.isActive)
        }
    }

    @Test
    fun widthScalesOnlyTheSideSignal() {
        val narrow = StereoWidthAudioProcessor().apply { widthPercent = 0 }.configured(true)
        assertEquals(listOf<Short>(0, 0), narrow.run(pcm(1000, -1000)))
        val wide = StereoWidthAudioProcessor().apply { widthPercent = 150 }.configured(true)
        assertEquals(listOf<Short>(1500, -1500), wide.run(pcm(1000, -1000)))
        // The centre (L = R) is the same at every width, and 100 % is the original.
        assertEquals(listOf<Short>(800, 800), wide.run(pcm(800, 800)))
        val original = StereoWidthAudioProcessor().apply { widthPercent = 100 }.configured(true)
        assertEquals(listOf<Short>(1200, -300), original.run(pcm(1200, -300)))
    }

    @Test
    fun widthNeverClipsHard() {
        val wide = StereoWidthAudioProcessor().apply { widthPercent = 150 }.configured(true)
        val out = wide.run(pcm(Short.MAX_VALUE, Short.MIN_VALUE))
        // 1.5 × full scale would wrap around without protection; the soft limit keeps the sign near full scale.
        assertTrue(out[0] > 29_000)
        assertTrue(out[1] < -29_000)
    }

    @Test
    fun warmthKeepsQuietSamplesAndRoundsPeaks() {
        val warmth = WarmthAudioProcessor().apply { level = 3 }.configured(true)
        val quiet = warmth.run(pcm(100, -100))
        assertTrue(abs(quiet[0] - 100) <= 3 && abs(quiet[1] + 100) <= 3)
        assertEquals(listOf<Short>(0, 0), warmth.run(pcm(0, 0)))
        val loud = warmth.run(pcm(30_000, -30_000))
        assertTrue(loud[0] in 1 until 30_000)
        assertTrue(loud[1] in -30_000 until 0)
        // Stronger warmth bends more.
        val light = WarmthAudioProcessor().apply { level = 1 }.configured(true).run(pcm(30_000, 0))
        assertTrue(light[0] > loud[0])
    }

    @Test
    fun fastTanhStaysCloseToTanhAndSaturates() {
        var x = -4f
        while (x <= 4f) {
            assertEquals(kotlin.math.tanh(x), WarmthAudioProcessor.fastTanh(x), 0.03f)
            val step = 0.001f
            val slope = (WarmthAudioProcessor.fastTanh(x + step) - WarmthAudioProcessor.fastTanh(x - step)) / (2 * step)
            assertEquals(slope, WarmthAudioProcessor.fastTanhSlope(x), 0.01f)
            assertTrue(WarmthAudioProcessor.fastTanhSlope(x) >= 0f)
            x += 0.05f
        }
        assertEquals(1f, WarmthAudioProcessor.fastTanh(3f), 0f)
        assertEquals(-1f, WarmthAudioProcessor.fastTanh(-10f), 0f)
    }

    @Test
    fun crossfeedFeedsTheOtherEarAndKeepsTheCentre() {
        val crossfeed = CrossfeedAudioProcessor().apply { level = 3 }.configured(true)
        val leftOnly = crossfeed.run(steady(10_000, 0, 4_000)).takeLast(2)
        assertTrue("right ear hears the left channel", leftOnly[1] > 500)
        assertTrue("left ear is quieter", leftOnly[0] < 10_000)
        // bs2b normalises its gain so a centred low tone (L = R) keeps its level.
        val fresh = CrossfeedAudioProcessor().apply { level = 2 }.configured(true)
        val centre = fresh.run(steady(10_000, 10_000, 4_000)).takeLast(2)
        assertEquals(10_000f, centre[0].toFloat(), 50f)
        assertEquals(10_000f, centre[1].toFloat(), 50f)
    }

    @Test
    fun reverbLeavesATailAfterTheSound() {
        val reverb = ReverbAudioProcessor().apply {
            preset = ReverbPreset.HALL
            mixPercent = 100
        }.configured(true)
        reverb.run(steady(20_000, 20_000, 200))
        val tail = reverb.run(steady(0, 0, 4_000))
        assertTrue("the room still rings after the input stopped", tail.any { abs(it.toInt()) > 20 })
        // Switching it off (it fades out over 30 ms) and on again starts from silence: no stale echo.
        reverb.effectEnabled = false
        reverb.run(steady(0, 0, 4_000))
        reverb.effectEnabled = true
        assertTrue(reverb.run(steady(0, 0, 4_000)).all { it.toInt() == 0 })
    }

    @Test
    fun softLimitOnlyActsAboveTheKnee() {
        assertEquals(0.5f, StereoEffectProcessor.softLimit(0.5f), 0f)
        assertTrue(StereoEffectProcessor.softLimit(3f) <= 1f && StereoEffectProcessor.softLimit(0.95f) < 0.95f)
        assertTrue(StereoEffectProcessor.softLimit(-3f) >= -1f)
        assertNotEquals(Short.MIN_VALUE, StereoEffectProcessor.toPcm(-5f))
    }

    @Test
    fun chainFollowsSettingsHeadphonesAndMono() {
        val klang = KlangProcessors()
        val all = AudioSettings(
            crossfeedEnabled = true, widthEnabled = true, reverbEnabled = true, warmthEnabled = true,
            widthPercent = 70, reverbPreset = ReverbPreset.CLUB, reverbMix = 40, warmthLevel = 1, crossfeedLevel = 3
        )
        klang.apply(all, headphones = true)
        assertTrue(klang.crossfeed.effectEnabled && klang.width.effectEnabled)
        assertTrue(klang.warmth.effectEnabled && klang.reverb.effectEnabled)
        assertEquals(70, klang.width.widthPercent)
        assertEquals(ReverbPreset.CLUB, klang.reverb.preset)
        assertEquals(40, klang.reverb.mixPercent)
        assertEquals(1, klang.warmth.level)
        assertEquals(3, klang.crossfeed.level)
        // On the loudspeaker the headphone crossfeed is off.
        klang.apply(all, headphones = false)
        assertFalse(klang.crossfeed.effectEnabled)
        // After the mono mix there is no stereo picture left to cross or widen.
        klang.apply(all.copy(mono = true), headphones = true)
        assertTrue(klang.mono.monoEnabled)
        assertFalse(klang.crossfeed.effectEnabled || klang.width.effectEnabled)
        assertTrue(klang.warmth.effectEnabled && klang.reverb.effectEnabled)
        assertEquals(5, klang.chain.size)
        assertTrue(klang.chain.first() === klang.mono && klang.chain.last() === klang.reverb)
    }
}
