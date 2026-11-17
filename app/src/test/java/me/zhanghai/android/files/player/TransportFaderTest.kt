/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TransportFaderTest {
    private var clock = 0L
    private val pending = mutableListOf<Pair<Long, Runnable>>()
    private val gains = mutableListOf<Float>()
    private val fader = TransportFader(
        { clock }, { action, delay -> pending += clock + delay to action },
        { action -> pending.removeAll { it.second === action } }
    ) { gains += it }

    private fun advance(ms: Long) {
        val end = clock + ms
        while (true) {
            val next = pending.filter { it.first <= end }.minByOrNull { it.first } ?: break
            pending.remove(next)
            clock = next.first
            next.second.run()
        }
        clock = end
    }

    /** Volume steps of a fade: monotonic and none larger than a tenth of full volume. */
    private fun assertGentle(values: List<Float>, falling: Boolean) {
        values.zipWithNext { a, b ->
            assertTrue("monotonic: $a then $b", if (falling) b <= a else b >= a)
            assertTrue("step %.3f".format(abs(b - a)), abs(b - a) <= 0.1f)
        }
    }

    @Test
    fun pauseFadesOutOver120MsBeforeItActs() {
        var paused = false
        fader.fadeOutThen { paused = true }
        advance(TransportFader.OUT_MS / 2)
        assertTrue("not yet paused halfway through the fade", !paused)
        assertEquals(0.5f, fader.gain, 0.06f)
        advance(TransportFader.OUT_MS)
        assertTrue(paused)
        assertGentle(gains.subList(0, gains.indexOf(0f) + 1), falling = true)
        // Paused, the volume is back at full, so anything that plays without the fader is not silent.
        assertEquals(1f, fader.gain, 0f)
    }

    @Test
    fun playStartsSilentAndFadesInOver80Ms() {
        var playing = false
        fader.fadeIn { playing = true }
        assertTrue(playing)
        assertEquals(0f, gains.first(), 0f)
        advance(TransportFader.IN_MS + 10)
        assertEquals(1f, fader.gain, 0f)
        assertGentle(gains, falling = false)
    }

    @Test
    fun aNewerCommandRunsTheOlderOneFirst() {
        val order = mutableListOf<String>()
        fader.fadeOutThen { order += "pause" }
        advance(30)
        fader.fadeIn { order += "play" }
        assertEquals(listOf("pause", "play"), order)
        advance(500)
        assertEquals(1f, fader.gain, 0f)
    }

    @Test
    fun nothingPlayingActsAtOnceAtFullVolume() {
        var done = false
        fader.immediate { done = true }
        assertTrue(done)
        assertEquals(1f, fader.gain, 0f)
    }

    @Test
    fun theCurveIsFlatAtBothEnds() {
        assertEquals(0f, TransportFader.cosineEase(0f), 0f)
        assertEquals(1f, TransportFader.cosineEase(1f), 1e-6f)
        assertEquals(0.5f, TransportFader.cosineEase(0.5f), 1e-6f)
        assertTrue(TransportFader.cosineEase(0.05f) < 0.01f)
    }
}
