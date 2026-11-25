/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Volume levelling for files without ReplayGain or R128 tags: the file is decoded once in the background and measured
 * after ITU-R BS.1770 ([LoudnessMeter]); the result is the gain to the ReplayGain 2.0 reference (-18 LUFS) plus the
 * sample peak, cached per path, size and modification time. Only local files: a network file would have to be
 * downloaded a second time.
 *
 * Media files are untrusted input, so the decoding is bounded: the format must be plausible (1-8 channels,
 * 8-384 kHz, 16-bit or float PCM) at the start and after every format change, the decoded length may exceed the
 * declared duration by at most 10 % (never more than 4 h), the whole run may take at most 30 s, and a decoder that
 * keeps answering "try again" is given up. A failed file is remembered like a measured one and never decoded again.
 */
class LoudnessAnalysis internal constructor(
    private val cache: Cache,
    private val openSource: (File) -> Source?,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxMillis: Long = MAX_MILLIS
) {
    constructor(context: Context) : this(
        PreferencesCache(context), ::openMediaCodec
    )

    /** With a longer time limit: a two-hour film's sound takes longer to decode than a song (Ordo Video). */
    constructor(context: Context, maxMillis: Long) : this(
        PreferencesCache(context), ::openMediaCodec, maxMillis = maxMillis
    )

    /** Key-value store of results; SharedPreferences in the app, a map in tests. */
    internal interface Cache {
        fun get(key: String): String?
        fun put(key: String, value: String)
    }

    /** A decoder as the measuring loop sees it. */
    internal interface Source : AutoCloseable {
        /** Channels, sample rate, float output and declared duration (µs, 0 = unknown) of the start format. */
        val format: Format
        fun next(): Step
    }

    internal data class Format(val channels: Int, val sampleRate: Int, val float: Boolean, val durationUs: Long = 0)

    internal sealed class Step {
        object TryAgain : Step()
        object End : Step()
        class FormatChanged(val format: Format) : Step()
        /** Decoded PCM in native order; [release] hands the buffer back to the decoder. */
        class Data(val buffer: ByteBuffer, val release: () -> Unit) : Step()
    }

    sealed class Cached {
        class Measured(val info: ReplayGainInfo) : Cached()
        object Failed : Cached()
    }

    fun cached(file: File): Cached? = cache.get(key(file))?.let(::parse)

    /** The remembered result, else a fresh measurement; null for a failed or silent file. Slow: not on main. */
    fun cachedOrMeasure(file: File): ReplayGainInfo? = when (val cached = cached(file)) {
        is Cached.Measured -> cached.info
        Cached.Failed -> null
        null -> measure(file)
    }

    /**
     * Decodes and measures [file]; null when it cannot be decoded or holds nothing loud enough. Slow: not on main.
     * With [remember] false nothing is written (Ordo Video's "Don't remember places").
     */
    fun measure(file: File, remember: Boolean = true): ReplayGainInfo? {
        val result = try {
            openSource(file)?.use { measure(it) }
        } catch (_: Throwable) {
            // Out of memory, a decoder crash, an interrupt: this file is not measured, playback goes on.
            null
        }
        if (Thread.currentThread().isInterrupted) return null
        if (remember) cache.put(key(file), if (result == null) FAILED else "${result.gainDb};${result.peak ?: 0f}")
        return result
    }

    /** The bounded measuring loop. */
    internal fun measure(source: Source): ReplayGainInfo? {
        var format = source.format.takeIf(::plausible) ?: return null
        val start = now()
        val maxFrames = maxFrames(format)
        var meter = LoudnessMeter(format.sampleRate, format.channels)
        var frame = FloatArray(format.channels)
        var frames = 0L
        var idle = 0
        while (true) {
            if (Thread.currentThread().isInterrupted || now() - start > maxMillis) return null
            when (val step = source.next()) {
                Step.End -> break
                Step.TryAgain -> if (++idle > MAX_IDLE) return null
                is Step.FormatChanged -> {
                    format = step.format.takeIf(::plausible) ?: return null
                    meter = LoudnessMeter(format.sampleRate, format.channels)
                    frame = FloatArray(format.channels)
                    idle = 0
                }
                is Step.Data -> {
                    idle = 0
                    val buffer = step.buffer
                    val bytes = if (format.float) 4 else 2
                    while (buffer.remaining() >= format.channels * bytes) {
                        for (c in 0 until format.channels) {
                            frame[c] = if (format.float) buffer.getFloat() else buffer.getShort() / 32_768f
                        }
                        meter.add(frame)
                        frames++
                    }
                    step.release()
                    if (frames > maxFrames) return null
                }
            }
        }
        val lufs = meter.integrated() ?: return null
        return ReplayGainInfo((LoudnessMeter.REFERENCE_LUFS - lufs).toFloat(), meter.peak.takeIf { it > 0f })
    }

    private fun key(file: File) = "${file.path}|${file.length()}|${file.lastModified()}"

    private fun parse(value: String): Cached? {
        if (value == FAILED) return Cached.Failed
        val parts = value.split(';')
        val gain = parts.getOrNull(0)?.toFloatOrNull() ?: return null
        val peak = parts.getOrNull(1)?.toFloatOrNull()?.takeIf { it > 0f }
        return Cached.Measured(ReplayGainInfo(gain, peak))
    }

    private class PreferencesCache(context: Context) : Cache {
        private val preferences = context.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE)
        override fun get(key: String): String? = preferences.getString(key, null)
        override fun put(key: String, value: String) {
            preferences.edit().putString(key, value).apply()
        }
    }

    internal companion object {
        private const val CACHE_NAME = "audio_loudness"
        private const val FAILED = "x"
        private const val TIMEOUT_US = 10_000L
        internal const val MAX_MILLIS = 30_000L
        /** Consecutive "try again" answers (10 ms each) before a stuck decoder is given up: 5 s. */
        internal const val MAX_IDLE = 500
        private const val MAX_SECONDS = 4 * 60 * 60L

        internal fun plausible(format: Format): Boolean =
            format.channels in 1..8 && format.sampleRate in 8_000..384_000

        /** Declared duration + 10 %, never more than 4 h of audio. */
        internal fun maxFrames(format: Format): Long {
            val cap = MAX_SECONDS * format.sampleRate
            if (format.durationUs <= 0) return cap
            return minOf(cap, format.durationUs * format.sampleRate / 1_000_000 * 11 / 10)
        }

        private fun openMediaCodec(file: File): Source? {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(file.path)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: run {
                    extractor.release()
                    return null
                }
                extractor.selectTrack(track)
                val input = extractor.getTrackFormat(track)
                codec = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME)!!)
                codec.configure(input, null, null, 0)
                codec.start()
                return MediaCodecSource(extractor, codec, input)
            } catch (exception: Throwable) {
                runCatching { codec?.release() }
                extractor.release()
                throw exception
            }
        }
    }

    private class MediaCodecSource(
        private val extractor: MediaExtractor,
        private val codec: MediaCodec,
        input: MediaFormat
    ) : Source {
        override val format = Format(
            input.getInteger(MediaFormat.KEY_CHANNEL_COUNT), input.getInteger(MediaFormat.KEY_SAMPLE_RATE), false,
            if (input.containsKey(MediaFormat.KEY_DURATION)) input.getLong(MediaFormat.KEY_DURATION) else 0
        )
        private val info = MediaCodec.BufferInfo()
        private var inputDone = false

        override fun next(): Step {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            return when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val output = codec.outputFormat
                    val encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else AudioFormat.ENCODING_PCM_16BIT
                    // Only 16-bit and float PCM are read; anything else counts as implausible (no channels).
                    val supported = encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT
                    Step.FormatChanged(
                        Format(
                            if (supported) output.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0,
                            output.getInteger(MediaFormat.KEY_SAMPLE_RATE), encoding == AudioFormat.ENCODING_PCM_FLOAT,
                            format.durationUs
                        )
                    )
                }
                index >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 && info.size == 0) {
                        codec.releaseOutputBuffer(index, false)
                        Step.End
                    } else {
                        val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val last = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (last) inputDone = true
                        Step.Data(buffer) {
                            codec.releaseOutputBuffer(index, false)
                            if (last) ended = true
                        }
                    }
                }
                else -> if (ended) Step.End else Step.TryAgain
            }
        }

        private var ended = false

        override fun close() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }
    }
}
