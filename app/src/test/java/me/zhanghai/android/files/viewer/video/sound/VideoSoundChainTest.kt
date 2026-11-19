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

    // ---- Dialog distance in LU (Torcoli et al., JAES 2019): synthetic speech and music with a known mix ----------

    /** "Speech": 15 partials 800-1500 Hz with a 4 Hz syllable envelope; deterministic. */
    private fun speech(n: Int): Float {
        var sum = 0.0
        for (k in 0 until 15) sum += sin(2 * PI * (800 + 50 * k) * n / rate + k * 1.3)
        return (sum / 15 * (0.55 + 0.45 * sin(2 * PI * 4 * n / rate))).toFloat()
    }

    /** "Music": partials away from the speech band (60-120 Hz and 7-9 kHz), a different one per [voice]. */
    private fun music(n: Int, voice: Int): Float {
        var sum = 0.0
        for (k in 0 until 4) {
            sum += sin(2 * PI * (60 + 20 * k + 3 * voice) * n / rate + voice + k)
            sum += sin(2 * PI * (7_000 + 500 * k + 37 * voice) * n / rate + 2 * voice + k)
        }
        return (sum / 8).toFloat()
    }

    /** Integrated loudness (BS.1770) of interleaved stereo shorts, leaving out the first [skipFrames]. */
    private fun lufs(samples: List<Short>, skipFrames: Int): Double {
        val meter = me.zhanghai.android.files.player.LoudnessMeter(rate, 2)
        val frame = FloatArray(2)
        for (i in skipFrames until samples.size / 2) {
            frame[0] = samples[2 * i] / 32_768f
            frame[1] = samples[2 * i + 1] / 32_768f
            meter.add(frame)
        }
        return meter.integrated()!!
    }

    /** Feeds [seconds] of [channels]-channel PCM made by [sample] in 20 ms buffers and returns the output shorts. */
    private fun feed(
        processor: AudioProcessor,
        channels: Int,
        seconds: Int,
        sample: (n: Int, channel: Int) -> Float
    ): List<Short> {
        val out = ArrayList<Short>()
        val frames = rate / 50
        for (buffer in 0 until seconds * 50) {
            processor.queueInput(pcm(frames, channels) { frame, channel -> sample(buffer * frames + frame, channel) })
            val output = processor.output
            while (output.hasRemaining()) out += output.getShort()
        }
        return out
    }

    @Test
    fun stereoDialogReachesItsTargetDistance() {
        for ((level, target) in listOf(VideoSoundMath.DIALOG_LIGHT to 10.0, VideoSoundMath.DIALOG_STRONG to 15.0)) {
            val stage = DialogStage().apply { this.level = level; effectEnabled = true }
            val processor = KlangAudioProcessor(arrayOf(stage), dither = false)
            processor.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
            processor.flush()
            // Speech in the middle, music mostly at the sides plus a little in the middle: about 0 LU apart.
            val speechPart = { n: Int, _: Int -> 0.12f * speech(n) }
            val musicPart = { n: Int, channel: Int ->
                0.02f * music(n, 0) + (if (channel == 0) 0.05f else -0.05f) * music(n, 1)
            }
            feed(processor, 2, 20) { n, channel -> speechPart(n, channel) + musicPart(n, channel) }
            val before = stage.distanceLu
            stage.steering.hold = true
            val speechOut = lufs(feed(processor, 2, 8, speechPart), rate)
            val musicOut = lufs(feed(processor, 2, 8, musicPart), rate)
            val reached = speechOut - musicOut
            println("dialog stereo, target %.0f LU: measured before %.1f LU, reached %.2f LU".format(target, before, reached))
            assertEquals("stereo, target $target", target, reached, 1.0)
        }
    }

    @Test
    fun surroundDialogReachesItsTargetDistance() {
        for ((level, target) in listOf(VideoSoundMath.DIALOG_LIGHT to 10.0, VideoSoundMath.DIALOG_STRONG to 15.0)) {
            val downmix = DownmixProcessor().apply {
                dialogTargetLu = VideoSoundMath.dialogTargetLu(level)!!
            }
            downmix.configure(AudioProcessor.AudioFormat(rate, 6, C.ENCODING_PCM_16BIT))
            downmix.flush()
            // 5.1: speech only in the centre, music in the four other main channels, LFE silent.
            val speechPart = { n: Int, channel: Int -> if (channel == 2) 0.2f * speech(n) else 0f }
            val musicPart = { n: Int, channel: Int ->
                if (channel == 2 || channel == 3) 0f else 0.05f * music(n, channel)
            }
            feed(downmix, 6, 20) { n, channel -> speechPart(n, channel) + musicPart(n, channel) }
            val before = downmix.distanceLu
            downmix.steering.hold = true
            val speechOut = lufs(feed(downmix, 6, 8, speechPart), rate)
            val musicOut = lufs(feed(downmix, 6, 8, musicPart), rate)
            val reached = speechOut - musicOut
            println("dialog 5.1, target %.0f LU: measured before %.1f LU, reached %.2f LU".format(target, before, reached))
            assertEquals("5.1, target $target", target, reached, 1.0)
        }
    }

    @Test
    fun dialogAlreadyFarEnoughIsLeftAlone() {
        val stage = DialogStage().apply { level = VideoSoundMath.DIALOG_LIGHT; effectEnabled = true }
        val processor = KlangAudioProcessor(arrayOf(stage), dither = false)
        processor.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = { n: Int, channel: Int ->
            0.1f * speech(n) + (if (channel == 0) 0.002f else -0.002f) * music(n, 1)
        }
        feed(processor, 2, 10, input)
        assertTrue(stage.distanceLu > 10f)
        assertEquals(0f, stage.steering.dialogDb, 0.01f)
        assertEquals(0f, stage.steering.restDb, 0.01f)
    }

    /**
     * The whole video chain with everything on (5.1 downmix, dialog strong, night, true-peak limiter, delay), 60 s of 48 kHz
     * 5.1 in 20 ms buffers. Prints the share of one core; a JVM figure, not a phone's (docs/VIDEO-SOUND.md).
     */
    @Test
    fun cpuOfTheWholeChain() {
        val downmix = DownmixProcessor().apply { dialogTargetLu = 15f }
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
    fun dialogSteeringMovesWithoutACrack() {
        // Speech plus side music: the change of target moves both gains; no step bigger than the steady sound's.
        val dialog = DialogStage().apply { level = VideoSoundMath.DIALOG_LIGHT; effectEnabled = true }
        val processor = KlangAudioProcessor(arrayOf(dialog), dither = false)
        processor.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = { n: Int, channel: Int -> 0.08f * speech(n) + (if (channel == 0) 0.07f else -0.07f) * music(n, 1) }
        val settled = feed(processor, 2, 10, input)
        dialog.level = VideoSoundMath.DIALOG_STRONG
        val moving = feed(processor, 2, 3, input)
        fun maxStep(samples: List<Short>) = (2 until samples.size step 2).maxOf { abs(samples[it] - samples[it - 2]) }
        assertTrue(maxStep(moving) <= maxStep(settled.takeLast(settled.size / 2)) * 2.2)
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
    fun privacyGatesForLoudnessAndDelay() {
        val local = "file:///storage/emulated/0/Movies/a.mp4"
        assertEquals(LoudnessPolicy.CACHE, loudnessPolicy(local, noHistory = false))
        assertEquals(LoudnessPolicy.MEMORY, loudnessPolicy(local, noHistory = true))
        assertEquals(LoudnessPolicy.SKIP, loudnessPolicy("ordo-vault://item/7", noHistory = false))
        assertEquals(LoudnessPolicy.SKIP, loudnessPolicy("smb://nas/film.mkv", noHistory = false))
        assertEquals(LoudnessPolicy.SKIP, loudnessPolicy("content://x/1", noHistory = false))
        assertEquals(LoudnessPolicy.SKIP, loudnessPolicy("", noHistory = false))
        assertTrue(remembersDelay(local))
        assertTrue(remembersDelay("smb://nas/film.mkv"))
        assertFalse(remembersDelay("ordo-vault://item/7"))
        assertFalse(remembersDelay("ORDO-VAULT://item/7"))
        assertFalse(remembersDelay("content://x/1"))
        assertFalse(remembersDelay("no-scheme"))
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
