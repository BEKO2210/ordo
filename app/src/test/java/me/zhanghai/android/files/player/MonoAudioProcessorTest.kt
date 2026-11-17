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

class MonoAudioProcessorTest {
    private val stereo = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)

    private fun configured(mono: Boolean) = MonoAudioProcessor().apply {
        monoEnabled = mono
        configure(stereo)
        flush()
    }

    private fun pcm(vararg samples: Short): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
            samples.forEach { putShort(it) }
            flip()
        }

    private fun ByteBuffer.shorts(): List<Short> = List(remaining() / 2) { getShort() }

    // Media3 polls the pipeline with EMPTY_BUFFER before any data; that used to throw "The source buffer is
    // this buffer" and stop every stereo song.
    @Test
    fun emptyInputBeforeAnyDataDoesNotThrow() {
        for (mono in listOf(false, true)) {
            val processor = configured(mono)
            processor.queueInput(AudioProcessor.EMPTY_BUFFER)
            assertFalse(processor.getOutput().hasRemaining())
        }
    }

    @Test
    fun stereoPassesThroughUnchanged() {
        val processor = configured(false)
        assertTrue(processor.isActive)
        processor.queueInput(pcm(100, -200, 300, 400))
        assertEquals(listOf<Short>(100, -200, 300, 400), processor.getOutput().shorts())
    }

    @Test
    fun monoMixesBothChannels() {
        val processor = configured(true)
        processor.queueInput(AudioProcessor.EMPTY_BUFFER)
        processor.queueInput(pcm(100, 300, -1000, 0))
        assertEquals(listOf<Short>(200, 200, -500, -500), processor.getOutput().shorts())
    }

    @Test
    fun monoSourcesBypassTheProcessor() {
        val processor = MonoAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT))
        assertFalse(processor.isActive)
    }
}
