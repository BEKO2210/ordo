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
import java.nio.ByteOrder

/**
 * Volume levelling for files without ReplayGain or R128 tags: the file is decoded once in the background and measured
 * after ITU-R BS.1770 ([LoudnessMeter]); the result is the gain to the ReplayGain 2.0 reference (-18 LUFS) plus the
 * sample peak, cached per path, size and modification time. Only local files: a network file would have to be
 * downloaded a second time.
 */
class LoudnessAnalysis(context: Context) {
    private val cache = context.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE)

    fun cached(file: File): ReplayGainInfo? = cache.getString(key(file), null)?.let(::parse)

    /** Decodes and measures [file]; null when it cannot be decoded or holds nothing loud enough. Slow: not on main. */
    fun measure(file: File): ReplayGainInfo? {
        val result = decodeAndMeasure(file) ?: return null
        cache.edit().putString(key(file), "${result.gainDb};${result.peak ?: 0f}").apply()
        return result
    }

    private fun key(file: File) = "${file.path}|${file.length()}|${file.lastModified()}"

    private fun parse(value: String): ReplayGainInfo? {
        val parts = value.split(';')
        val gain = parts.getOrNull(0)?.toFloatOrNull() ?: return null
        val peak = parts.getOrNull(1)?.toFloatOrNull()?.takeIf { it > 0f }
        return ReplayGainInfo(gain, peak)
    }

    private fun decodeAndMeasure(file: File): ReplayGainInfo? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var meter = LoudnessMeter(rate, channels)
            var frame = FloatArray(channels)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
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
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val output = codec.outputFormat
                    channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    meter = LoudnessMeter(rate, channels)
                    frame = FloatArray(channels)
                } else if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val float = encoding == AudioFormat.ENCODING_PCM_FLOAT
                    val bytes = if (float) 4 else 2
                    while (buffer.remaining() >= channels * bytes) {
                        for (c in 0 until channels) {
                            frame[c] = if (float) buffer.getFloat() else buffer.getShort() / 32_768f
                        }
                        meter.add(frame)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            val lufs = meter.integrated() ?: return null
            return ReplayGainInfo((LoudnessMeter.REFERENCE_LUFS - lufs).toFloat(), meter.peak.takeIf { it > 0f })
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    companion object {
        private const val CACHE_NAME = "audio_loudness"
        private const val TIMEOUT_US = 10_000L
    }
}
