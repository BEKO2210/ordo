/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The owner heard a short crack whenever a button was pressed. A 1 kHz stereo sine (A = 0.5, 48 kHz) runs through
 * Ordo's whole processor chain in 64-frame buffers (the setting changes between two of them, as it does live). The
 * largest sample-to-sample step around the change may be at most 1.2 × the largest step of the steady signal before or
 * after it (docs/AUDIO-QUALITAET-RECHERCHE.md, 1.4): a hard switch is a jump, a fade is not.
 */
class ClickFreeTest {
    private val stereo = AudioProcessor.AudioFormat(SAMPLE_RATE, 2, C.ENCODING_PCM_16BIT)

    /** Runs the sine through the chain; [change] happens before buffer [CHANGE_BUFFER]. Returns left, right per frame. */
    private fun render(
        start: AudioSettings,
        change: AudioSettings,
        headphones: Boolean,
        headphonesAfter: Boolean
    ): Array<FloatArray> {
        val klang = KlangProcessors()
        klang.apply(start, headphones)
        klang.chain.forEach {
            it.configure(stereo)
            it.flush()
        }
        val left = FloatArray(BUFFERS * FRAMES)
        val right = FloatArray(BUFFERS * FRAMES)
        var frame = 0
        for (buffer in 0 until BUFFERS) {
            if (buffer == CHANGE_BUFFER) klang.apply(change, headphonesAfter)
            var data = ByteBuffer.allocateDirect(FRAMES * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until FRAMES) {
                val phase = 2 * PI * 1_000 * (frame + i) / SAMPLE_RATE
                data.putShort((AMPLITUDE * sin(phase) * 32_767).toInt().toShort())
                data.putShort((AMPLITUDE * sin(phase + PI / 2) * 32_767).toInt().toShort())
            }
            data.flip()
            klang.chain.forEach { processor ->
                if (!processor.isActive) return@forEach
                processor.queueInput(data)
                data = processor.getOutput()
            }
            while (data.remaining() >= 4) {
                left[frame] = data.getShort() / 32_768f
                right[frame] = data.getShort() / 32_768f
                frame++
            }
        }
        return arrayOf(left, right)
    }

    private fun maxDelta(signal: FloatArray, from: Int, to: Int): Float {
        var max = 0f
        for (n in maxOf(from, 1) until minOf(to, signal.size)) max = maxOf(max, abs(signal[n] - signal[n - 1]))
        return max
    }

    private fun assertClickFree(
        name: String,
        start: AudioSettings,
        change: AudioSettings,
        headphonesAfter: Boolean = true
    ) {
        val output = render(start, change, true, headphonesAfter)
        val at = CHANGE_BUFFER * FRAMES
        for (channel in output) {
            val before = maxDelta(channel, SETTLE, at - WINDOW)
            val after = maxDelta(channel, at + SETTLE, channel.size)
            val around = maxDelta(channel, at - WINDOW, at + SETTLE)
            val allowed = maxOf(before, after) * 1.2f
            assertTrue("$name: step %.4f around the change, allowed %.4f".format(around, allowed), around <= allowed)
        }
    }

    private val off = AudioSettings()

    @Test
    fun monoSwitchesWithoutACrack() {
        assertClickFree("mono on", off, off.copy(mono = true))
        assertClickFree("mono off", off.copy(mono = true), off)
    }

    @Test
    fun widthSwitchesAndStepsWithoutACrack() {
        assertClickFree("width on", off, off.copy(widthEnabled = true, widthPercent = 150))
        assertClickFree("width off", off.copy(widthEnabled = true, widthPercent = 0), off)
        assertClickFree(
            "width step", off.copy(widthEnabled = true, widthPercent = 150), off.copy(widthEnabled = true, widthPercent = 20)
        )
    }

    @Test
    fun warmthSwitchesAndStepsWithoutACrack() {
        assertClickFree("warmth on", off, off.copy(warmthEnabled = true, warmthLevel = 3))
        assertClickFree("warmth off", off.copy(warmthEnabled = true, warmthLevel = 3), off)
        assertClickFree(
            "warmth step", off.copy(warmthEnabled = true, warmthLevel = 1), off.copy(warmthEnabled = true, warmthLevel = 3)
        )
    }

    @Test
    fun roomSoundSwitchesAndStepsWithoutACrack() {
        assertClickFree("room sound on", off, off.copy(crossfeedEnabled = true, crossfeedLevel = 3))
        assertClickFree("room sound off", off.copy(crossfeedEnabled = true, crossfeedLevel = 3), off)
        assertClickFree(
            "room sound step", off.copy(crossfeedEnabled = true, crossfeedLevel = 1),
            off.copy(crossfeedEnabled = true, crossfeedLevel = 3)
        )
        // Pulling the headphones out switches it off too.
        val on = off.copy(crossfeedEnabled = true, crossfeedLevel = 3)
        assertClickFree("room sound unplugged", on, on, headphonesAfter = false)
    }

    @Test
    fun reverbSwitchesAndChangesRoomWithoutACrack() {
        val hall = off.copy(reverbEnabled = true, reverbPreset = ReverbPreset.HALL, reverbMix = 100)
        assertClickFree("reverb on", off, hall)
        assertClickFree("reverb off", hall, off)
        assertClickFree("reverb room", hall.copy(reverbPreset = ReverbPreset.ROOM), hall)
        assertClickFree("reverb mix", hall.copy(reverbMix = 10), hall)
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val FRAMES = 64
        private const val BUFFERS = 400
        private const val CHANGE_BUFFER = 200
        // Long enough for every fade and for the reverb tail to build up before "after" is measured.
        private const val SETTLE = 8_000
        /** 10 ms before the change. */
        private const val WINDOW = 480
        private const val AMPLITUDE = 0.5
    }
}
