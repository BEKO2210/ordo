/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Ordo's sound chain (KlangAudioProcessor) and its stages: mono, crossfeed, stereo width, warmth and reverb, all in 16-bit
 * stereo like real music. Most checks run one stage alone and without dither, so samples compare exactly.
 */
class KlangChainTest {
    private val stereo = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)

    /** One stage alone in the chain. */
    private class Single(val stage: KlangStage, dither: Boolean = false) {
        val processor = KlangAudioProcessor(arrayOf(stage), dither)
        var effectEnabled: Boolean
            get() = stage.effectEnabled
            set(value) {
                stage.effectEnabled = value
            }
    }

    private fun all(): List<KlangStage> = listOf(
        MonoStage(), CrossfeedStage(), WidthStage().apply { widthPercent = 150 },
        WarmthStage().apply { level = 3 }, ReverbStage().apply { mixPercent = 100 }
    )

    private fun KlangStage.configured(on: Boolean, dither: Boolean = false): Single = Single(this, dither).also {
        effectEnabled = on
        it.processor.configure(stereo)
        it.processor.flush()
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

    private fun Single.run(input: ByteBuffer): List<Short> {
        processor.queueInput(input)
        return processor.getOutput().shorts()
    }

    // The 0.13.2 lesson: Media3 polls with EMPTY_BUFFER before any data, and copying it into itself throws.
    @Test
    fun emptyInputBeforeAnyDataDoesNotThrow() {
        for (on in listOf(false, true)) {
            all().forEach { stage ->
                val single = stage.configured(on)
                single.processor.queueInput(AudioProcessor.EMPTY_BUFFER)
                assertFalse(single.processor.getOutput().hasRemaining())
            }
        }
    }

    @Test
    fun stereoPassesThroughBitForBitWhenOff() {
        all().forEach { stage ->
            val single = stage.configured(false, dither = true)
            assertTrue(single.processor.isActive)
            // Nothing on: no float round trip, no limiter, no dither.
            assertEquals(listOf<Short>(100, -200, 30000, -32768), single.run(pcm(100, -200, 30000, -32768)))
        }
    }

    @Test
    fun monoAndFloatSourcesBypassTheChain() {
        val processor = KlangProcessors().processor
        processor.configure(AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT))
        assertFalse(processor.isActive)
        processor.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT))
        assertFalse(processor.isActive)
    }

    @Test
    fun monoMixesBothChannels() {
        val mono = MonoStage().configured(true)
        assertEquals(listOf<Short>(200, 200, -500, -500), mono.run(pcm(100, 300, -1000, 0)))
        // Constant gain: opposite full-scale channels cancel, equal ones stay.
        assertEquals(listOf<Short>(0, 0), mono.run(pcm(32_767, -32_767)))
        // Equal full-scale channels stay equal; the one limiter turns them down to its ceiling (-1.5 dBFS).
        val loud = MonoStage().configured(true).run(pcm(-32_768, -32_768))
        assertEquals(loud[0], loud[1])
        assertTrue("${loud[0]}", loud[0] in -27_600..-27_000)
    }

    @Test
    fun ditherIsTriangularAndQuiet() {
        // Width on at 100 % changes nothing but makes the chain work, so silence comes out as dither alone.
        val single = WidthStage().apply { widthPercent = 100 }.configured(true, dither = true)
        val out = single.run(steady(0, 0, 20_000)).map { it.toInt() }
        assertTrue("never more than 1 LSB", out.all { it in -1..1 })
        val rms = kotlin.math.sqrt(out.sumOf { it * it.toDouble() } / out.size)
        // TPDF of 2 LSB peak to peak plus rounding: about 0.5 LSB RMS, centred.
        assertTrue("rms %.3f".format(rms), rms in 0.3..0.7)
        assertTrue(abs(out.average()) < 0.05)
    }

    @Test
    fun widthScalesOnlyTheSideSignal() {
        val narrow = WidthStage().apply { widthPercent = 0 }.configured(true)
        assertEquals(listOf<Short>(0, 0), narrow.run(pcm(1000, -1000)))
        val wide = WidthStage().apply { widthPercent = 150 }.configured(true)
        assertEquals(listOf<Short>(1500, -1500), wide.run(pcm(1000, -1000)))
        // The centre (L = R) is the same at every width, and 100 % is the original.
        assertEquals(listOf<Short>(800, 800), wide.run(pcm(800, 800)))
        val original = WidthStage().apply { widthPercent = 100 }.configured(true)
        assertEquals(listOf<Short>(1200, -300), original.run(pcm(1200, -300)))
    }

    @Test
    fun widthNeverClipsHard() {
        val wide = WidthStage().apply { widthPercent = 150 }.configured(true)
        val out = wide.run(pcm(Short.MAX_VALUE, Short.MIN_VALUE))
        // 1.5 × full scale would wrap around without protection; the limiter keeps the sign, at its ceiling.
        assertTrue(out[0] in 27_000..27_600)
        assertTrue(out[1] in -27_600..-27_000)
    }

    @Test
    fun warmthKeepsQuietSamplesAndRoundsPeaks() {
        val warmth = WarmthStage().apply { level = 3 }.configured(true)
        val quiet = warmth.run(pcm(100, -100))
        assertTrue(abs(quiet[0] - 100) <= 3 && abs(quiet[1] + 100) <= 3)
        assertEquals(listOf<Short>(0, 0), warmth.run(pcm(0, 0)))
        val loud = warmth.run(pcm(30_000, -30_000))
        assertTrue(loud[0] in 1 until 30_000)
        assertTrue(loud[1] in -30_000 until 0)
        // Stronger warmth bends more.
        val light = WarmthStage().apply { level = 1 }.configured(true).run(pcm(30_000, 0))
        assertTrue(light[0] > loud[0])
    }

    @Test
    fun fastTanhStaysCloseToTanhAndSaturates() {
        var x = -4f
        while (x <= 4f) {
            assertEquals(kotlin.math.tanh(x), WarmthStage.fastTanh(x), 0.03f)
            val step = 0.001f
            val slope = (WarmthStage.fastTanh(x + step) - WarmthStage.fastTanh(x - step)) / (2 * step)
            assertEquals(slope, WarmthStage.fastTanhSlope(x), 0.01f)
            assertTrue(WarmthStage.fastTanhSlope(x) >= 0f)
            x += 0.05f
        }
        assertEquals(1f, WarmthStage.fastTanh(3f), 0f)
        assertEquals(-1f, WarmthStage.fastTanh(-10f), 0f)
    }

    @Test
    fun crossfeedFeedsTheOtherEarAndKeepsTheCentre() {
        val crossfeed = CrossfeedStage().apply { level = 3 }.configured(true)
        val leftOnly = crossfeed.run(steady(10_000, 0, 4_000)).takeLast(2)
        assertTrue("right ear hears the left channel", leftOnly[1] > 500)
        assertTrue("left ear is quieter", leftOnly[0] < 10_000)
        // bs2b normalises its gain so a centred low tone (L = R) keeps its level.
        val fresh = CrossfeedStage().apply { level = 2 }.configured(true)
        val centre = fresh.run(steady(10_000, 10_000, 4_000)).takeLast(2)
        assertEquals(10_000f, centre[0].toFloat(), 50f)
        assertEquals(10_000f, centre[1].toFloat(), 50f)
    }

    @Test
    fun reverbLeavesATailAfterTheSound() {
        val reverb = ReverbStage().apply {
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
    fun theLimiterHoldsTheCeilingAndRecovers() {
        val wide = WidthStage().apply { widthPercent = 150 }.configured(true)
        val ceiling = (KlangAudioProcessor.CEILING * 32_768).toInt() + 1
        val loud = wide.run(steady(Short.MAX_VALUE, Short.MIN_VALUE, 100))
        assertTrue("never above the ceiling", loud.all { abs(it.toInt()) <= ceiling })
        // After the peak the gain comes back: a quiet passage plays at its own level again.
        wide.run(steady(0, 0, 44_100))
        assertEquals(listOf<Short>(1500, -1500), wide.run(pcm(1000, -1000)))
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
        assertTrue(klang.mono.effectEnabled)
        assertFalse(klang.crossfeed.effectEnabled || klang.width.effectEnabled)
        assertTrue(klang.warmth.effectEnabled && klang.reverb.effectEnabled)
        // One processor for the whole chain: one float conversion, one limiter, one dither.
        assertEquals(1, klang.chain.size)
        assertTrue(klang.chain.single() === klang.processor)
    }
}
