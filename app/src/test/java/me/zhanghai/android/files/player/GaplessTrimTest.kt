/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.Log
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TrimmingAudioProcessor
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ogg.OggExtractor
import androidx.media3.extractor.text.SubtitleParser
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Gapless trim from Media3 1.11.1 extractors + TrimmingAudioProcessor (DefaultAudioSink path). */
@UnstableApi
class GaplessTrimTest {
    // lame.mp3 LAME Info tag: delay=576, padding=1404. XingFrame adds 528+1 for Lavc/Lavf/LAME.
    private val mp3Delay = 576 + 529
    private val mp3Padding = 1404 - 529

    // iTunSMPB " 00000000 00000840 000001C0 …" → 2112/448. udta fills GaplessInfoHolder first; a
    // qualifying audio elst overwrites it (BoxParser.parseStbl). IGNORE_EDIT_LISTS keeps iTunSMPB.
    private val itunesDelay = 2112
    private val itunesPadding = 448

    // edit_list.m4a elst media_time=1024; mdhd duration equals edit end → padding 0.
    private val editListDelay = 1024
    private val editListPadding = 0

    // OpusHead[10..11] LE = 312; OpusUtil initData[1] = 312*1e9/48000 ns (native endian).
    private val opusPreSkip = 312
    private val opusPreSkipNs = 6_500_000L

    @Before
    fun silenceMedia3Log() {
        Log.setLogLevel(Log.LOG_LEVEL_OFF) // default logger → android.util.Log ("not mocked")
        // Its "running in a test?" probe reads Build.FINGERPRINT, null on the JVM stub; enforce the limit explicitly.
        ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
    }

    @Test
    fun mp3LameXingDelayAndPaddingReachFormat() {
        val format = extractFormat(Mp3Extractor(), "/gapless/lame.mp3")
        assertEquals(mp3Delay, format.encoderDelay)
        assertEquals(mp3Padding, format.encoderPadding)
        assertTrue(format.encoderDelay > 0 && format.encoderPadding > 0)
    }

    @Test
    fun m4aItunesSmpbWhenEditListIgnored() {
        val flags =
            Mp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA or Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS
        val format =
            extractFormat(Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, flags), "/gapless/itunsmpb.m4a")
        assertEquals(itunesDelay, format.encoderDelay)
        assertEquals(itunesPadding, format.encoderPadding)
    }

    // What Ordo plays with (default flags): the edit list wins over iTunSMPB when both exist.
    @Test
    fun m4aEditListWinsOverItunesSmpbByDefault() {
        val format = extractFormat(
            Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, Mp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA),
            "/gapless/itunsmpb.m4a"
        )
        assertEquals(editListDelay, format.encoderDelay)
        assertEquals(editListPadding, format.encoderPadding)
    }

    @Test
    fun m4aEditListPriming() {
        val format =
            extractFormat(
                Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, Mp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA),
                "/gapless/edit_list.m4a"
            )
        assertEquals(editListDelay, format.encoderDelay)
        assertEquals(editListPadding, format.encoderPadding)
    }

    @Test
    fun opusPreSkipInInitializationData() {
        val format = extractFormat(OggExtractor(), "/gapless/preskip.opus")
        assertTrue(format.initializationData.size >= 2)
        val head = format.initializationData[0]
        assertEquals(opusPreSkip, (head[10].toInt() and 0xff) or ((head[11].toInt() and 0xff) shl 8))
        val ns = ByteBuffer.wrap(format.initializationData[1]).order(ByteOrder.nativeOrder()).long
        assertEquals(opusPreSkipNs, ns)
    }

    @Test
    fun trimmingAudioProcessorDropsDelayAndPaddingFrames() {
        // DefaultAudioSink.configure → setTrimFrameCount(encoderDelay, encoderPadding).
        val delay = 3
        val padding = 2
        val total = 20
        val pcm = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)
        val trim = TrimmingAudioProcessor()
        trim.setTrimFrameCount(delay, padding)
        trim.configure(pcm)
        trim.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = ByteBuffer.allocateDirect(total * 4).order(ByteOrder.nativeOrder())
        for (frame in 0 until total) {
            input.putShort(frame.toShort())
            input.putShort(0)
        }
        input.flip()
        trim.queueInput(input)
        trim.configure(pcm) // gapless handoff: end-trim discards held endBuffer on EOS
        trim.queueEndOfStream()
        val left = ArrayList<Short>()
        while (!trim.isEnded) {
            val buf = trim.getOutput()
            while (buf.remaining() >= 4) {
                left.add(buf.short)
                buf.short
            }
        }
        assertEquals(total - delay - padding, left.size)
        assertEquals(delay.toShort(), left[0])
    }

    private fun extractFormat(extractor: Extractor, resource: String, maxReads: Int = 400): Format {
        val data = javaClass.getResourceAsStream(resource)!!.readBytes()
        val reader = BytesReader(data)
        val output = FormatCatchingOutput()
        extractor.init(output)
        var input = DefaultExtractorInput(reader, 0, data.size.toLong())
        val seek = PositionHolder()
        repeat(maxReads) {
            when (val result = extractor.read(input, seek)) {
                Extractor.RESULT_END_OF_INPUT -> {
                    extractor.release()
                    return requireNotNull(output.format) { "EOF without format: $resource" }
                }
                Extractor.RESULT_SEEK -> {
                    reader.pos = seek.position.toInt()
                    input = DefaultExtractorInput(reader, seek.position, data.size.toLong())
                }
            }
            val format = output.format ?: return@repeat
            if (format.sampleRate <= 0) return@repeat
            if (format.encoderDelay > 0 || format.encoderPadding > 0 ||
                format.initializationData.isNotEmpty()
            ) {
                extractor.release()
                return format
            }
        }
        extractor.release()
        return requireNotNull(output.format) { "no format from $resource" }
    }

    /** Own DataReader: ByteArrayDataSource needs android.net.Uri. */
    private class BytesReader(private val data: ByteArray) : DataReader {
        var pos = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, data.size - pos)
            System.arraycopy(data, pos, buffer, offset, n)
            pos += n
            return n
        }
    }

    private class FormatCatchingOutput : ExtractorOutput, TrackOutput {
        var format: Format? = null

        override fun track(id: Int, type: Int): TrackOutput = this

        override fun endTracks() {}

        override fun seekMap(seekMap: SeekMap) {}

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int
        ): Int {
            val buf = ByteArray(minOf(4096, length))
            var left = length
            var read = 0
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size, left))
                if (n == C.RESULT_END_OF_INPUT) {
                    if (allowEndOfInput && read == 0) return C.RESULT_END_OF_INPUT
                    throw java.io.EOFException()
                }
                left -= n
                read += n
            }
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?
        ) {}
    }
}
