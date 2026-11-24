/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import me.zhanghai.android.files.player.LoudnessAnalysis.Format
import me.zhanghai.android.files.player.LoudnessAnalysis.Source
import me.zhanghai.android.files.player.LoudnessAnalysis.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Media files are untrusted: the loudness measurement must stop on absurd formats, endless decoders and bad files. */
class LoudnessAnalysisTest {
    private val cache = object : LoudnessAnalysis.Cache {
        val values = HashMap<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String) {
            values[key] = value
        }
    }

    /** A 1 kHz sine at -20 dBFS, [seconds] long, or endless when null; [steps] may prepend other answers. */
    private class FakeSource(
        override val format: Format,
        private val seconds: Double?,
        private val endlessTryAgain: Boolean = false,
        private val later: Format? = null
    ) : Source {
        private var frames = 0L
        var closed = false

        override fun next(): Step {
            if (endlessTryAgain) return Step.TryAgain
            if (later != null && frames == 0L) {
                frames = 1
                return Step.FormatChanged(later)
            }
            if (seconds != null && frames >= seconds * format.sampleRate) return Step.End
            val buffer = ByteBuffer.allocate(1_024 * format.channels * 2).order(ByteOrder.nativeOrder())
            repeat(1_024) {
                val value = (0.1 * sin(2 * PI * 1_000 * frames / format.sampleRate) * 32_767).toInt().toShort()
                repeat(format.channels) { buffer.putShort(value) }
                frames++
            }
            buffer.flip()
            return Step.Data(buffer) {}
        }

        override fun close() {
            closed = true
        }
    }

    private val stereo = Format(2, 48_000, false)

    @Test
    fun absurdFormatsAreRefused() {
        val analysis = LoudnessAnalysis(cache, { null })
        assertNull(analysis.measure(FakeSource(Format(0, 48_000, false), 1.0)))
        assertNull(analysis.measure(FakeSource(Format(99, 48_000, false), 1.0)))
        assertNull(analysis.measure(FakeSource(Format(2, 1_000_000, false), 1.0)))
        assertNull(analysis.measure(FakeSource(Format(2, 100, false), 1.0)))
        // Plausible at the start, absurd after a format change.
        assertNull(analysis.measure(FakeSource(stereo, 1.0, later = Format(2, 5_000_000, false))))
        assertNotNull(analysis.measure(FakeSource(stereo, 3.0)))
    }

    @Test
    fun aDecoderThatNeverDeliversIsGivenUp() {
        val analysis = LoudnessAnalysis(cache, { null })
        assertNull(analysis.measure(FakeSource(stereo, null, endlessTryAgain = true)))
    }

    @Test
    fun anEndlessStreamStopsAtItsDeclaredLength() {
        val analysis = LoudnessAnalysis(cache, { null })
        // Declares 2 s but never ends: stopped once it passes 2.2 s.
        assertNull(analysis.measure(FakeSource(stereo.copy(durationUs = 2_000_000), null)))
        assertEquals(48_000L * 4 * 60 * 60, LoudnessAnalysis.maxFrames(stereo))
        assertEquals(105_600L, LoudnessAnalysis.maxFrames(stereo.copy(durationUs = 2_000_000)))
    }

    @Test
    fun theWallClockLimitStopsASlowDecoder() {
        var clock = 0L
        val analysis = LoudnessAnalysis(cache, { null }, now = { clock.also { clock += 1_000 } })
        assertNull(analysis.measure(FakeSource(stereo, null)))
        assertTrue("stopped near 30 s", clock in LoudnessAnalysis.MAX_MILLIS..LoudnessAnalysis.MAX_MILLIS + 5_000)
    }

    @Test
    fun aBadFileIsRememberedAndNeverDecodedAgain() {
        var opened = 0
        val file = File("/nonexistent/song.mp3")
        val analysis = LoudnessAnalysis(cache, {
            opened++
            throw OutOfMemoryError("hostile file")
        })
        assertNull(analysis.measure(file))
        assertEquals(LoudnessAnalysis.Cached.Failed, analysis.cached(file))
        // The service asks the cache first: a failed file is not opened a second time.
        assertEquals(1, opened)
    }

    @Test
    fun aGoodFileIsMeasuredAndCached() {
        val source = FakeSource(stereo, 5.0)
        val analysis = LoudnessAnalysis(cache, { source })
        val file = File("/music/song.flac")
        val info = analysis.measure(file)!!
        // -20 dBFS stereo 1 kHz reads about -20 LUFS: +2 dB to -18 LUFS.
        assertEquals(2f, info.gainDb, 0.15f)
        assertTrue(source.closed)
        val cached = analysis.cached(file) as LoudnessAnalysis.Cached.Measured
        assertEquals(info.gainDb, cached.info.gainDb, 0f)
    }

    @Test
    fun cachedOrMeasureMeasuresOnceThenUsesTheCache() {
        var opened = 0
        val analysis = LoudnessAnalysis(cache, {
            opened++
            FakeSource(stereo, 5.0)
        })
        val file = File("/music/again.flac")
        val first = analysis.cachedOrMeasure(file)!!
        val second = analysis.cachedOrMeasure(file)!!
        assertEquals(first.gainDb, second.gainDb, 0f)
        assertEquals(1, opened)
    }

    @Test
    fun cachedOrMeasureSkipsAFailedFile() {
        var opened = 0
        val analysis = LoudnessAnalysis(cache, {
            opened++
            null
        })
        val file = File("/music/bad.mp3")
        assertNull(analysis.cachedOrMeasure(file))
        assertNull(analysis.cachedOrMeasure(file))
        assertEquals(1, opened)
    }
}
