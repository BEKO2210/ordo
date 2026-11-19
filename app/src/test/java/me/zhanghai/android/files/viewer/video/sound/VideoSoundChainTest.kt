/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.sound

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import me.zhanghai.android.files.player.AudioSettings
import me.zhanghai.android.files.player.EqualizerPreset
import me.zhanghai.android.files.player.KlangAudioProcessor
import me.zhanghai.android.files.player.KlangStage
import me.zhanghai.android.files.player.TruePeakLimiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** The video sound chain: downmix, A/V delay, dialog enhancer, night mode, settings codec and track wording. */
class VideoSoundChainTest {
    private val rate = 48_000

    private fun pcm(frames: Int, channels: Int, sample: (frame: Int, channel: Int) -> Float): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.nativeOrder())
        for (frame in 0 until frames) for (channel in 0 until channels) {
            buffer.putShort((sample(frame, channel) * 32_767f).toInt().toShort())
        }
        buffer.flip()
        return buffer
    }

    private fun run(processor: AudioProcessor, format: AudioProcessor.AudioFormat, input: ByteBuffer): ShortArray {
        processor.configure(format)
        processor.flush()
        val out = mutableListOf<Short>()
        processor.queueInput(input)
        val output = processor.output
        while (output.hasRemaining()) out += output.getShort()
        return out.toShortArray()
    }

    @Test
    fun downmixKeepsTheCentreInBothChannelsAndDropsLfe() {
        val processor = DownmixProcessor()
        val format = AudioProcessor.AudioFormat(rate, 6, C.ENCODING_PCM_16BIT)
        // Only the centre (index 2) plays 0.5; the LFE (index 3) plays full scale and must not be heard.
        val out = run(processor, format, pcm(100, 6) { _, ch -> if (ch == 2) 0.5f else if (ch == 3) 1f else 0f })
        assertEquals(200, out.size)
        val expected = 0.5f * VideoSoundMath.centreGain(VideoSoundMath.DIALOG_OFF) * VideoSoundMath.NORMALISE
        assertEquals(expected, out[198] / 32_768f, 0.002f)
        assertEquals(out[198], out[199])
    }

    @Test
    fun downmixPassesStereoBy() {
        val processor = DownmixProcessor()
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            processor.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        )
        assertFalse(processor.isActive)
    }

    @Test
    fun downmixNeverClipsAndRaisesTheCentreWithTheDialogLevel() {
        val processor = DownmixProcessor()
        val format = AudioProcessor.AudioFormat(rate, 6, C.ENCODING_PCM_16BIT)
        val loud = run(processor, format, pcm(2_000, 6) { _, ch -> if (ch == 3) 0f else 1f })
        assertTrue(loud.all { abs(it / 32_768f) <= KlangAudioProcessor.CEILING + 0.001f })
        processor.centreGain = VideoSoundMath.centreGain(VideoSoundMath.DIALOG_STRONG)
        processor.flush() // the limiter starts at full gain again
        processor.queueInput(pcm(100, 6) { _, ch -> if (ch == 2) 0.2f else 0f })
        processor.queueInput(pcm(100, 6) { _, ch -> if (ch == 2) 0.2f else 0f })
        val output = processor.output
        var last = 0
        while (output.hasRemaining()) last = output.getShort().toInt()
        val strong = 0.2f * VideoSoundMath.centreGain(VideoSoundMath.DIALOG_STRONG) * VideoSoundMath.NORMALISE
        assertEquals(strong, last / 32_768f, 0.002f)
    }

    @Test
    fun delayInsertsSilenceOrDropsFrames() {
        val format = AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)
        val later = AvDelayProcessor().apply { delayMs = 100 }
        val out = run(later, format, pcm(1_000, 2) { _, _ -> 0.5f })
        assertEquals((4_800 + 1_000) * 2, out.size)
        assertTrue(out.take(4_800 * 2).all { it.toInt() == 0 })
        assertTrue(out.drop(4_800 * 2).all { it.toInt() != 0 })

        val earlier = AvDelayProcessor().apply { delayMs = -50 }
        earlier.configure(format)
        earlier.flush()
        earlier.queueInput(pcm(2_000, 2) { _, _ -> 0.5f })
        assertFalse(earlier.output.hasRemaining())
        earlier.queueInput(pcm(2_000, 2) { _, _ -> 0.5f })
        // 2400 frames are dropped in all: 1600 of the second buffer remain.
        assertEquals(1_600 * 4, earlier.output.remaining())

        val none = AvDelayProcessor()
        assertEquals(400, run(none, format, pcm(200, 2) { _, _ -> 0.25f }).size)
    }

    /** RMS in dBFS of a stage's output for a sine of [frequency] at [amplitude], after a settling second. */
    private fun levelDb(stage: KlangStage, frequency: Double, amplitude: Float): Double {
        val processor = KlangAudioProcessor(arrayOf(stage), dither = false)
        stage.effectEnabled = true
        val frames = rate * 2
        val out = run(
            processor, AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT),
            pcm(frames, 2) { frame, _ -> amplitude * sin(2 * PI * frequency * frame / rate).toFloat() }
        )
        var sum = 0.0
        val from = rate * 2 // the second half, both channels
        for (i in from until out.size) sum += (out[i] / 32_768.0).let { it * it }
        return 20 * log10(sqrt(sum / (out.size - from)))
    }

    @Test
    fun nightModeNarrowsTheRange() {
        val quietIn = 20 * log10(0.01 / sqrt(2.0))
        val loudIn = 20 * log10(0.7 / sqrt(2.0))
        val quiet = levelDb(NightStage(), 1_000.0, 0.01f)
        val loud = levelDb(NightStage(), 1_000.0, 0.7f)
        assertTrue("quiet speech is lifted ($quietIn -> $quiet)", quiet > quietIn + 6)
        assertTrue("the range shrinks (${loudIn - quietIn} -> ${loud - quiet})", loud - quiet < (loudIn - quietIn) / 2)
    }

    @Test
    fun dialogLiftsThePresenceBandMoreThanTheLows() {
        fun gain(frequency: Double) = levelDb(DialogStage().apply { level = VideoSoundMath.DIALOG_STRONG }, frequency, 0.05f) -
            20 * log10(0.05 / sqrt(2.0))
        assertTrue(gain(2_500.0) > gain(100.0) + 4)
    }

    /**
     * The whole video chain with everything on (5.1 downmix, dialog strong, night, true-peak limiter, delay), 60 s of 48 kHz
     * 5.1 in 20 ms buffers. Prints the share of one core; a JVM figure, not a phone's (docs/VIDEO-SOUND.md).
     */
    @Test
    fun cpuOfTheWholeChain() {
        val downmix = DownmixProcessor().apply { centreGain = VideoSoundMath.centreGain(VideoSoundMath.DIALOG_STRONG) }
        val dialog = DialogStage().apply { level = VideoSoundMath.DIALOG_STRONG; effectEnabled = true }
        val night = NightStage().apply { effectEnabled = true }
        val klang = KlangAudioProcessor(arrayOf(dialog, night))
        val delay = AvDelayProcessor().apply { delayMs = 100 }
        val truePeak = TruePeakLimiter().apply { armed = true }
        downmix.configure(AudioProcessor.AudioFormat(rate, 6, C.ENCODING_PCM_16BIT))
        klang.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        truePeak.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        delay.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        listOf(downmix, klang, truePeak, delay).forEach { it.flush() }
        val frames = rate / 50
        val input = pcm(frames, 6) { frame, ch -> 0.3f * sin(2 * PI * (200 + ch * 150) * frame / rate).toFloat() }
        fun pass() {
            input.rewind()
            downmix.queueInput(input)
            klang.queueInput(downmix.output)
            truePeak.queueInput(klang.output)
            delay.queueInput(truePeak.output)
            delay.output.position(delay.output.limit())
        }
        repeat(500) { pass() } // warm-up for the JIT
        val buffers = 60 * 50
        val start = System.nanoTime()
        repeat(buffers) { pass() }
        val share = (System.nanoTime() - start) / 60e9 * 100
        println("Video sound chain (downmix, dialog, night, true peak, delay), all on, 48 kHz 5.1: %.2f %% of one core (JVM)".format(share))
        assertTrue("far below real time: $share %", share < 25)
    }

    /**
     * A steady 1 kHz tone through one stage; [change] runs before buffer [at] (20 ms buffers). Returns the left channel
     * and checks that no sample step around the change is bigger than the biggest step of the steady sound before or
     * after it (+20 %), as ClickFreeTest does for Ordo Musik's stages.
     */
    private fun assertClickFree(name: String, stage: KlangStage, amplitude: Float, change: () -> Unit) {
        val processor = KlangAudioProcessor(arrayOf(stage), dither = false)
        processor.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val frames = rate / 50
        val at = 25
        val left = ArrayList<Float>()
        for (buffer in 0 until 60) {
            if (buffer == at) change()
            val input = pcm(frames, 2) { frame, _ ->
                amplitude * sin(2 * PI * 1_000 * (buffer * frames + frame) / rate).toFloat()
            }
            processor.queueInput(input)
            val output = processor.output
            while (output.hasRemaining()) {
                left += output.getShort() / 32_768f
                output.getShort()
            }
        }
        fun maxDelta(from: Int, to: Int) = (from + 1 until to).maxOf { abs(left[it] - left[it - 1]) }
        val changeAt = at * frames
        val settle = rate / 5
        val before = maxDelta(settle, changeAt - 100)
        val after = maxDelta(changeAt + settle, left.size)
        val around = maxDelta(changeAt - 100, changeAt + settle)
        assertTrue(
            "$name: step %.4f around the change, allowed %.4f".format(around, maxOf(before, after) * 1.2f),
            around <= maxOf(before, after) * 1.2f
        )
    }

    @Test
    fun dialogLevelGlidesWithoutACrack() {
        val dialog = DialogStage().apply { level = VideoSoundMath.DIALOG_LIGHT; effectEnabled = true }
        assertClickFree("dialog light to strong", dialog, 0.05f) { dialog.level = VideoSoundMath.DIALOG_STRONG }
        // Arrived: the presence peak is the strong level's after the ramp.
        assertEquals(7f, dialog.presenceGainDb, 0.01f)
        val back = DialogStage().apply { level = VideoSoundMath.DIALOG_STRONG; effectEnabled = true }
        assertClickFree("dialog strong to light", back, 0.05f) { back.level = VideoSoundMath.DIALOG_LIGHT }
    }

    @Test
    fun nightModeSwitchesWithoutACrack() {
        val night = NightStage()
        assertClickFree("night on over a loud tone", night, 0.5f) { night.effectEnabled = true }
        assertClickFree("night off over a loud tone", night, 0.5f) { night.effectEnabled = false }
        val quiet = NightStage()
        assertClickFree("night on over a quiet tone", quiet, 0.02f) { quiet.effectEnabled = true }
    }

    @Test
    fun delayStoreCodecAndSpatialDecision() {
        assertEquals(150 to 42L, VideoDelayStore.decode(VideoDelayStore.encode(150, 42L)))
        assertEquals(1000 to 1L, VideoDelayStore.decode("5000:1"))
        assertEquals(null, VideoDelayStore.decode("x:1"))
        assertEquals(null, VideoDelayStore.decode(null))
        assertTrue(VideoSpatial.useSystemSpatializer(true, true, true, true))
        assertFalse(VideoSpatial.useSystemSpatializer(true, true, false, true))
        assertFalse(VideoSpatial.useSystemSpatializer(false, true, true, true))
        assertFalse(VideoSpatial.useSystemSpatializer(true, true, true, false))
    }

    @Test
    fun downmixPassesChannelsTheSystemSpatializes() {
        val processor = DownmixProcessor().apply { passThroughChannels = setOf(6) }
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            processor.configure(AudioProcessor.AudioFormat(rate, 6, C.ENCODING_PCM_16BIT))
        )
        assertEquals(2, processor.configure(AudioProcessor.AudioFormat(rate, 8, C.ENCODING_PCM_16BIT)).channelCount)
    }

    @Test
    fun settingsRoundTripAndBadInputFallsBack() {
        val value = VideoSoundSettings(
            AudioSettings(equalizerEnabled = true, preset = EqualizerPreset.VOCAL, bandGainsDb = EqualizerPreset.VOCAL.gainsDb, mono = true, balance = -30),
            VideoSoundMath.DIALOG_STRONG, true
        )
        assertEquals(value, VideoSoundCodec.decode(VideoSoundCodec.encode(value)))
        assertEquals(VideoSoundSettings(), VideoSoundCodec.decode("{broken"))
        assertEquals(VideoSoundMath.DIALOG_STRONG, VideoSoundCodec.decode("""{"dialog":9}""").dialogLevel)
    }

    @Test
    fun trackRowsNameLanguageCodecChannelsAndUnplayable() {
        val format = VideoAudioTrackSheet.Track("de", null, MimeTypes.AUDIO_AC3, 6)
        val row = VideoAudioTrackSheet.describe(format, 1, false, Locale.GERMAN, { "Spur $it" }, { "$it Kanäle" }, "nicht abspielbar")
        assertEquals("Deutsch", row.title)
        assertEquals("Dolby Digital (AC3) · 5.1 · nicht abspielbar", row.detail)
        val bare = VideoAudioTrackSheet.describe(VideoAudioTrackSheet.Track(null, null, null, -1), 2, true, Locale.ENGLISH, { "Track $it" }, { "$it ch" }, "x")
        assertEquals("Track 2", bare.title)
        assertEquals("", bare.detail)
    }
}
