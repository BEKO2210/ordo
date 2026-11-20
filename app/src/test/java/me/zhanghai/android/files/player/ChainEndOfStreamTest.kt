/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import com.google.common.collect.ImmutableList
import me.zhanghai.android.files.viewer.video.sound.AvDelayProcessor
import me.zhanghai.android.files.viewer.video.sound.DownmixProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A track ends when the sink's pipeline reports ended after exactly the frames that went in. Media3's pipeline calls
 * queueEndOfStream on a processor again on every pass once the one before has ended; a processor that emits its tail
 * on each call never ends, and the player keeps playing silence past the duration (Tab S9, 20.11.2026).
 */
class ChainEndOfStreamTest {
    private val rate = 48_000
    private val frames = 10_000

    private fun drain(processors: List<AudioProcessor>, channels: Int = 2): Pair<Int, Boolean> {
        val pipeline = AudioProcessingPipeline(ImmutableList.copyOf(processors))
        val outFormat = pipeline.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
        pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val outFrameBytes = 2 * outFormat.channelCount
        var out = 0L
        fun take() {
            val buffer = pipeline.getOutput()
            out += buffer.remaining()
            buffer.position(buffer.limit())
        }
        val input = ByteBuffer.allocateDirect(frames * 2 * channels).order(ByteOrder.nativeOrder())
        while (input.hasRemaining()) input.putShort(1_000)
        input.flip()
        while (input.hasRemaining()) {
            pipeline.queueInput(input)
            take()
        }
        pipeline.queueEndOfStream()
        var passes = 0
        while (!pipeline.isEnded && passes < 1_000) {
            take()
            passes++
        }
        return (out / outFrameBytes).toInt() to pipeline.isEnded
    }

    @Test
    fun limiterAloneEndsWithItsTail() {
        val (out, ended) = drain(listOf(TruePeakLimiter().apply { armed = true }))
        assertTrue("not ended, $out frames out", ended)
        assertEquals(frames, out)
    }

    @Test
    fun musicChainEnds() {
        val (out, ended) = drain(KlangProcessors().chain.toList())
        assertTrue("not ended, $out frames out", ended)
        assertEquals(frames, out)
    }

    @Test
    fun videoChainEnds() {
        val chain = listOf(DownmixProcessor(), KlangAudioProcessor(arrayOf(MonoStage())), TruePeakLimiter(),
            AvDelayProcessor())
        val (out, ended) = drain(chain)
        assertTrue("not ended, $out frames out", ended)
        assertEquals(frames, out)
        // 5.1 through Ordo's downmix to stereo.
        val (surround, surroundEnded) = drain(chain, channels = 6)
        assertTrue("not ended, $surround frames out", surroundEnded)
        assertEquals(frames, surround)
    }
}
