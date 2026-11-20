/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.headphones

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import me.zhanghai.android.files.player.AudioSettings
import me.zhanghai.android.files.player.AudioSettingsCodec
import me.zhanghai.android.files.player.KlangAudioProcessor
import me.zhanghai.android.files.player.KlangStage
import me.zhanghai.android.files.player.LoudnessMeter
import me.zhanghai.android.files.viewer.video.sound.DownmixProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Ordo's headphone pack: virtual surround, equal-loudness shelves, AutoEq correction. */
class HeadphonePackTest {
    private val rate = 48_000
    private val stereo = AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)

    private fun pcm(frames: Int, channels: Int, sample: (frame: Int, channel: Int) -> Float): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.nativeOrder())
        for (frame in 0 until frames) for (channel in 0 until channels) {
            buffer.putShort((sample(frame, channel) * 32_767f).toInt().coerceIn(-32_768, 32_767).toShort())
        }
        buffer.flip()
        return buffer
    }

    /** [seconds] of [channels]-channel audio through [processor] in 20 ms buffers; [before] runs ahead of each. */
    private fun feed(
        processor: AudioProcessor,
        channels: Int,
        seconds: Double,
        before: (buffer: Int) -> Unit = {},
        sample: (n: Int, channel: Int) -> Float
    ): ShortArray {
        val out = ArrayList<Short>()
        val frames = rate / 50
        for (buffer in 0 until (seconds * 50).toInt()) {
            before(buffer)
            processor.queueInput(pcm(frames, channels) { frame, channel -> sample(buffer * frames + frame, channel) })
            val output = processor.output
            while (output.hasRemaining()) out += output.getShort()
        }
        return out.toShortArray()
    }

    private fun sine(frequency: Double, amplitude: Float) = { n: Int, _: Int ->
        amplitude * sin(2 * PI * frequency * n / rate).toFloat()
    }

    /** RMS in dBFS of the left channel from [fromFrame] on. */
    private fun rmsDb(out: ShortArray, fromFrame: Int): Double {
        var sum = 0.0
        var count = 0
        for (i in 2 * fromFrame until out.size step 2) {
            val x = out[i] / 32_768.0
            sum += x * x
            count++
        }
        return 10 * log10(sum / count)
    }

    private fun maxStep(out: ShortArray, from: Int, to: Int): Int =
        (maxOf(1, from) until minOf(to, out.size / 2)).maxOf { abs(out[2 * it] - out[2 * it - 2]) }

    // ISO 226 -----------------------------------------------------------------------------------------------------

    @Test
    fun iso226ContoursPassThroughTheirOwnLevelAt1kHz() {
        val oneKilohertz = 17
        assertEquals(1000.0, EqualLoudness.FREQUENCIES_HZ[oneKilohertz], 0.0)
        for (phon in listOf(20.0, 40.0, 60.0, 80.0, 90.0)) {
            assertEquals(phon, EqualLoudness.splDb(oneKilohertz, phon), 0.05)
        }
        // ISO 226:2003 formula 1 with Table 1: the 40-phon contour at 20 Hz and 100 Hz, and 80 phon at 4 kHz
        // (values as tabulated by implementations of the standard, e.g. 99.85 dB at 20 Hz).
        assertEquals(99.85, EqualLoudness.splDb(0, 40.0), 0.05)
        assertEquals(64.4, EqualLoudness.splDb(7, 40.0), 0.1)
        assertEquals(78.3, EqualLoudness.splDb(23, 80.0), 0.1)
    }

    @Test
    fun iso226CorrectionIsZeroAtTheReferenceAndRisesInTheBassAsTheLevelFalls() {
        assertTrue(EqualLoudness.correctionDb(80.0).all { abs(it) < 1e-9 })
        assertTrue(EqualLoudness.shelfGainsDb(85.0).all { it == 0.0 })
        var previous = 0.0
        for (phon in 75 downTo 30 step 5) {
            val bass = EqualLoudness.correctionDb(phon.toDouble())[2]
            assertTrue("31.5 Hz at $phon phon: $bass", bass > previous)
            previous = bass
        }
        // Capped, so it stays gentle, also at a whisper.
        val quiet = EqualLoudness.shelfGainsDb(20.0)
        assertEquals(EqualLoudness.MAX_BASS_DB, quiet[0], 1e-9)
        assertTrue(quiet[1] <= EqualLoudness.MAX_TREBLE_DB)
    }

    @Test
    fun twoShelvesFollowTheContourDifferenceWithin1Point5Db() {
        // Where the caps do not bite (down to 60 phon), the fit is the research's acceptance: ±1.5 dB, 31.5 Hz-12.5 kHz.
        for (phon in listOf(75.0, 70.0, 65.0, 60.0)) {
            val gains = EqualLoudness.shelfGainsDb(phon)
            val response = EqualLoudness.responseDb(gains[0], gains[1])
            val target = EqualLoudness.correctionDb(phon)
            for (i in 2 until target.size) {
                assertEquals("$phon phon at ${EqualLoudness.FREQUENCIES_HZ[i]} Hz", target[i], response[i], 1.5)
            }
        }
    }

    @Test
    fun listeningLevelFromTheVolumeCurve() {
        assertEquals(0f, EqualLoudness.defaultCurveDb(15, 0, 15), 1e-4f)
        assertEquals(-58f, EqualLoudness.defaultCurveDb(0, 0, 100), 1e-4f)
        assertEquals(-40f, EqualLoudness.defaultCurveDb(20, 0, 100), 1e-4f)
        assertEquals(-28.5f, EqualLoudness.defaultCurveDb(40, 0, 100), 1e-4f)
        assertEquals(80.0, EqualLoudness.listeningPhon(-20f, 0), 1e-9)
        assertEquals(70.0, EqualLoudness.listeningPhon(-20f, -10), 1e-9)
    }

    @Test
    fun quietShelvesLiftTheBassByTheirGainAndAreOffAtTheReference() {
        val stages = HeadphoneStages()
        val settings = AudioSettings(quietFullEnabled = true)
        stages.apply(settings, headphones = true, attenuationDb = -10f)
        assertTrue("at 90 phon nothing runs", !stages.quiet.effectEnabled)
        stages.apply(settings, headphones = true, attenuationDb = -40f)
        assertTrue(stages.quiet.effectEnabled)
        val gains = EqualLoudness.shelfGainsDb(60.0)
        assertEquals(gains[0].toFloat(), stages.quiet.bassDb, 1e-4f)
        val processor = KlangAudioProcessor(arrayOf<KlangStage>(stages.quiet), dither = false)
        processor.configure(stereo)
        processor.flush()
        val low = rmsDb(feed(processor, 2, 1.0, sample = sine(31.5, 0.05f)), rate / 2)
        processor.flush()
        val mid = rmsDb(feed(processor, 2, 1.0, sample = sine(1000.0, 0.05f)), rate / 2)
        val expected = EqualLoudness.responseDb(gains[0], gains[1])
        assertEquals(expected[2] - expected[17], low - mid, 0.3)
    }

    @Test
    fun quietShelvesGlideWhenTheVolumeChanges() {
        val stages = HeadphoneStages()
        val settings = AudioSettings(quietFullEnabled = true)
        stages.apply(settings, headphones = true, attenuationDb = -30f)
        val processor = KlangAudioProcessor(arrayOf<KlangStage>(stages.quiet), dither = false)
        processor.configure(stereo)
        processor.flush()
        val out = feed(processor, 2, 1.2, before = { if (it == 25) stages.apply(settings, true, -60f) },
            sample = sine(60.0, 0.05f))
        val change = 25 * rate / 50
        val steady = maxOf(maxStep(out, rate / 5, change - 10), maxStep(out, change + rate / 5, out.size / 2))
        assertTrue(maxStep(out, change - 10, change + rate / 5) <= steady * 1.3)
    }

    // AutoEq ------------------------------------------------------------------------------------------------------

    @Test
    fun autoEqPresetsParseSmallAndSearchable() {
        val resource = javaClass.getResourceAsStream("/me/zhanghai/android/files/player/headphones/autoeq_presets.json")
        val size = resource!!.use { it.readBytes().size }
        assertTrue("$size bytes", size < 300_000)
        val all = AutoEqPresets.all
        assertEquals(500, all.size)
        assertTrue(all.all { it.types.size == 10 && it.preampDb <= 0f })
        assertTrue(all.all { profile -> profile.frequenciesHz.all { it in 10f..24_000f } })
        val hd650 = AutoEqPresets.search("hd650")
        assertTrue(hd650.any { it.name == "Sennheiser HD 650" })
        val sennheiser = AutoEqPresets.search("SENNHEISER hd 6")
        assertTrue(sennheiser.any { it.name == "Sennheiser HD 600" })
        assertTrue(sennheiser.all { it.name.startsWith("Sennheiser HD") && '6' in it.name })
        assertEquals(all.size, AutoEqPresets.search("  ").size)
        assertNotNull(AutoEqPresets.find("Apple AirPods Max"))
    }

    @Test
    fun autoEqParseReadsTypesAndValues() {
        val parsed = AutoEqPresets.parse(
            """{"profiles":[["Test Phone","oratory1990",-4.7,[1,105,-3.0,0.7,0,7273,3.6,2.41,2,10000,-5.5,0.7]]]}"""
        ).single()
        assertEquals("Test Phone", parsed.name)
        assertEquals(-4.7f, parsed.preampDb, 1e-6f)
        assertArrayEquals(intArrayOf(Rbj.LOW_SHELF, Rbj.PEAKING, Rbj.HIGH_SHELF), parsed.types)
        assertEquals(7273f, parsed.frequenciesHz[1], 0f)
        assertEquals(2.41f, parsed.qs[1], 1e-6f)
    }

    @Test
    fun autoEqCorrectionAppliesPreampAndFiltersAt1kHz() {
        val profile = AutoEqPresets.find("Sennheiser HD 650")!!
        val stages = HeadphoneStages()
        stages.apply(AudioSettings(headphoneEq = profile.name), headphones = true, attenuationDb = 0f)
        val processor = KlangAudioProcessor(arrayOf<KlangStage>(stages.correction), dither = false)
        processor.configure(stereo)
        processor.flush()
        val level = rmsDb(feed(processor, 2, 1.0, sample = sine(1000.0, 0.1f)), rate / 2)
        val input = 20 * log10(0.1 / sqrt(2.0))
        var expected = profile.preampDb.toDouble()
        val c = DoubleArray(5)
        for (i in profile.types.indices) {
            Rbj.coefficients(
                profile.types[i], profile.frequenciesHz[i].toDouble(), profile.gainsDb[i].toDouble(),
                profile.qs[i].toDouble(), rate, c
            )
            expected += Rbj.magnitudeDb(c, 1000.0, rate)
        }
        assertEquals(expected, level - input, 0.1)
        // On the speaker the correction is off.
        stages.apply(AudioSettings(headphoneEq = profile.name), headphones = false, attenuationDb = 0f)
        assertTrue(!stages.correction.effectEnabled)
    }

    @Test
    fun switchingHeadphoneModelsDoesNotCrack() {
        val stages = HeadphoneStages()
        stages.apply(AudioSettings(headphoneEq = "Sennheiser HD 650"), true, 0f)
        val processor = KlangAudioProcessor(arrayOf<KlangStage>(stages.correction), dither = false)
        processor.configure(stereo)
        processor.flush()
        val out = feed(processor, 2, 1.2, before = {
            if (it == 25) stages.apply(AudioSettings(headphoneEq = "Apple AirPods Max"), true, 0f)
        }, sample = sine(1000.0, 0.3f))
        val change = 25 * rate / 50
        val steady = maxOf(maxStep(out, rate / 5, change - 10), maxStep(out, change + rate / 5, out.size / 2))
        assertTrue(maxStep(out, change - 10, change + rate / 5) <= steady * 1.2)
    }

    @Test
    fun headphoneSettingsSurviveTheCodec() {
        val settings = AudioSettings(
            quietFullEnabled = true, quietCalibrationDb = -10, binauralEnabled = true, headphoneEq = "Sony WH-1000XM4"
        )
        assertEquals(settings, AudioSettingsCodec.decode(AudioSettingsCodec.encode(settings)))
        assertEquals(10, AudioSettings(quietCalibrationDb = 99).sanitized().quietCalibrationDb)
        val old = AudioSettingsCodec.decode("""{"eq":true}""")
        assertTrue(!old.binauralEnabled && old.headphoneEq.isEmpty() && old.quietCalibrationDb == 0)
    }

    // Binaural ----------------------------------------------------------------------------------------------------

    @Test
    fun hrirsPutTheFrontLeftSpeakerOnTheLeft() {
        val responses = Binaural.responses(6, rate)!!
        fun energy(x: FloatArray) = x.sumOf { (it * it).toDouble() }
        assertTrue(energy(responses[0][0]) > 4 * energy(responses[0][1]))
        assertTrue(energy(responses[1][1]) > 4 * energy(responses[1][0]))
        assertTrue(energy(responses[3][0]) == 0.0) // LFE left out
        val centre = 10 * log10(energy(responses[2][0]) / energy(responses[2][1]))
        assertTrue("centre off by $centre dB", abs(centre) < 2)
    }

    @Test
    fun resamplingKeepsTheResponseLevel() {
        val ir = Binaural.responses(6, 48_000)!![0][0]
        val resampled = Binaural.resample(ir, 48_000, 44_100)
        fun magnitudeDb(h: FloatArray, f: Double, fs: Int): Double {
            var re = 0.0
            var im = 0.0
            for (k in h.indices) {
                re += h[k] * kotlin.math.cos(2 * PI * f * k / fs)
                im -= h[k] * sin(2 * PI * f * k / fs)
            }
            return 10 * log10(re * re + im * im)
        }
        for (f in listOf(100.0, 1000.0, 5000.0)) {
            assertEquals("$f Hz", magnitudeDb(ir, f, 48_000), magnitudeDb(resampled, f, 44_100), 0.3)
        }
    }

    /** Independent pink-ish noise per channel, LFE silent. */
    private fun surroundNoise(channels: Int): (Int, Int) -> Float {
        val random = Random(7)
        val state = Array(channels) { DoubleArray(4) }
        val noise = Array(channels) { FloatArray(rate * 12) }
        for (channel in 0 until channels) for (n in noise[channel].indices) {
            // Paul Kellet's economy pink filter.
            val white = random.nextGaussian() * 0.05
            val s = state[channel]
            s[0] = 0.99765 * s[0] + white * 0.0990460
            s[1] = 0.96300 * s[1] + white * 0.2965164
            s[2] = 0.57000 * s[2] + white * 1.0526913
            noise[channel][n] = if (channel == 3) 0f else (s[0] + s[1] + s[2] + white * 0.1848).toFloat()
        }
        return { n, channel -> noise[channel][n] }
    }

    private fun lufs(out: ShortArray, skipFrames: Int): Double {
        val meter = LoudnessMeter(rate, 2)
        val frame = FloatArray(2)
        for (i in skipFrames until out.size / 2) {
            frame[0] = out[2 * i] / 32_768f
            frame[1] = out[2 * i + 1] / 32_768f
            meter.add(frame)
        }
        return meter.integrated()!!
    }

    @Test
    fun binauralLevelMatchesTheDownmix() {
        for (channels in listOf(6, 8)) {
            val format = AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT)
            val noise = surroundNoise(channels)
            fun measure(binaural: Boolean): Double {
                val downmix = DownmixProcessor().apply { this.binaural = binaural }
                downmix.configure(format)
                downmix.flush()
                return lufs(feed(downmix, channels, 10.0, sample = noise), rate)
            }
            val matrix = measure(false)
            val virtual = measure(true)
            println("Binaural $channels ch: downmix %.2f LUFS, virtual surround %.2f LUFS".format(matrix, virtual))
            assertEquals(matrix, virtual, 1.0)
        }
    }

    @Test
    fun binauralOffIsBitExactAndSwitchingCrossFades() {
        val format = AudioProcessor.AudioFormat(rate, 6, C.ENCODING_PCM_16BIT)
        val signal = { n: Int, channel: Int ->
            if (channel == 3) 0f else 0.2f * sin(2 * PI * (300 + 100 * channel) * n / rate).toFloat()
        }
        fun run(switchAt: Int?): ShortArray {
            val downmix = DownmixProcessor()
            downmix.configure(format)
            downmix.flush()
            return feed(downmix, 6, 1.2, before = { if (it == switchAt) downmix.binaural = true }, sample = signal)
        }
        val plain = run(null)
        // Off: exactly the BS.775 matrix, computed here on its own (below the limiter's ceiling, so no limiting).
        val matrix = me.zhanghai.android.files.viewer.video.sound.VideoSoundMath.downmixMatrix(
            6, me.zhanghai.android.files.viewer.video.sound.VideoSoundMath.centreGain(0)
        )!!
        val reference = ShortArray(plain.size) { index ->
            val frame = index / 2
            var sum = 0f
            for (channel in 0 until 6) {
                val sample = (signal(frame, channel) * 32_767f).toInt().toShort() / 32_768f
                sum += matrix[index % 2][channel] * sample
            }
            Math.round(sum * 32_768f).coerceIn(-32_768, 32_767).toShort()
        }
        assertArrayEquals(reference, plain)
        val switched = run(25)
        val change = 25 * rate / 50
        assertArrayEquals(plain.copyOfRange(0, 2 * change), switched.copyOfRange(0, 2 * change))
        val steady = maxOf(maxStep(switched, rate / 5, change - 10), maxStep(switched, change + rate / 5, switched.size / 2))
        assertTrue(maxStep(switched, change - 10, change + rate / 5) <= steady * 1.3)
    }

    @Test
    fun cpuOfVirtualSurround() {
        for (channels in listOf(6, 8)) {
            val format = AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT)
            val downmix = DownmixProcessor().apply { binaural = true }
            downmix.configure(format)
            downmix.flush()
            val frames = rate / 50
            val buffer = pcm(frames, channels) { frame, channel -> 0.1f * sin(frame * 0.01f * (channel + 1)) }
            fun pass() {
                buffer.rewind()
                downmix.queueInput(buffer)
                downmix.output
            }
            repeat(250) { pass() }
            val seconds = 60
            val start = System.nanoTime()
            repeat(seconds * 50) { pass() }
            val share = (System.nanoTime() - start) / 1e9 / seconds * 100
            println("Virtual surround $channels ch (downmix + convolution): %.2f %% of one core at 48 kHz".format(share))
            assertTrue(share < 15)
        }
    }
}
