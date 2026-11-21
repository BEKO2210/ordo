/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.subtitles

import org.junit.Assert.assertEquals
import org.junit.Test

/** The delay queue of text tracks inside the film: cue groups as Media3's text renderer sends them (onCues). */
class EmbeddedCueQueueTest {
    private fun queue() = EmbeddedCueQueue<String> { it.joinToString("\n") }

    private fun s(seconds: Double) = (seconds * 1_000_000).toLong()

    /** "Hallo" from 10 s, nothing from 12 s, "Tschüss" from 15 s, nothing from 17 s. */
    private fun film() = queue().apply {
        add(s(10.0), listOf("Hallo"))
        add(s(12.0), emptyList())
        add(s(15.0), listOf("Tschüss"))
        add(s(17.0), emptyList())
    }

    @Test
    fun withoutDelayEachGroupShowsFromItsTime() {
        val queue = film()
        assertEquals(emptyList<String>(), queue.cuesAt(s(9.9), 0, 0))
        assertEquals(listOf("Hallo"), queue.cuesAt(s(10.0), 0, 0))
        assertEquals(emptyList<String>(), queue.cuesAt(s(12.5), 0, 0))
        assertEquals(listOf("Tschüss"), queue.cuesAt(s(16.0), 0, 0))
    }

    @Test
    fun lateDelayShowsTheSameLinesLater() {
        val queue = film()
        assertEquals(emptyList<String>(), queue.cuesAt(s(11.0), s(1.5), 0))
        assertEquals(listOf("Hallo"), queue.cuesAt(s(11.5), s(1.5), 0))
        assertEquals(listOf("Hallo"), queue.cuesAt(s(13.4), s(1.5), 0))
        assertEquals(emptyList<String>(), queue.cuesAt(s(13.5), s(1.5), 0))
    }

    @Test
    fun earlyDelayUsesGroupsThatArrivedAhead() {
        // The renderer runs ahead (SubtitleLead), so the groups are already here before their time.
        val queue = film()
        assertEquals(listOf("Hallo"), queue.cuesAt(s(9.0), -s(1.0), 0))
        assertEquals(listOf("Tschüss"), queue.cuesAt(s(14.0), -s(1.0), 0))
    }

    @Test
    fun pauseKeepsTheLineBecauseOnlyThePositionCounts() {
        val queue = film()
        // Paused: the position stands, asked again and again it stays the same line.
        repeat(5) { assertEquals(listOf("Hallo"), queue.cuesAt(s(11.0), s(0.5), 0)) }
    }

    @Test
    fun speedDoesNotMatterBecauseTheQueueIsKeyedOnPosition() {
        // At 2x the position runs twice as fast; the same positions give the same lines, in film time.
        val queue = film()
        val atDoubleSpeed = listOf(10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0).map { queue.cuesAt(s(it), s(0.5), 0) }
        val atNormalSpeed = film().let { q -> listOf(10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0).map { q.cuesAt(s(it), s(0.5), 0) } }
        assertEquals(atNormalSpeed, atDoubleSpeed)
        assertEquals(listOf("Hallo"), atDoubleSpeed[1])
        assertEquals(emptyList<String>(), atDoubleSpeed[5])
    }

    @Test
    fun seekClearsAndTheNewPlaceFillsAgain() {
        val queue = film()
        queue.clear()
        assertEquals(0, queue.size)
        assertEquals(emptyList<String>(), queue.cuesAt(s(16.0), 0, 0))
        // The renderer sends the line at the new place again.
        queue.add(s(15.0), listOf("Tschüss"))
        assertEquals(listOf("Tschüss"), queue.cuesAt(s(16.0), 0, 0))
    }

    @Test
    fun aSecondSendForTheSameTimeReplacesAndOrderHolds() {
        val queue = queue()
        queue.add(s(5.0), listOf("b"))
        queue.add(s(1.0), listOf("a"))
        queue.add(s(5.0), listOf("b2"))
        assertEquals(2, queue.size)
        assertEquals(listOf("a"), queue.cuesAt(s(2.0), 0, 0))
        assertEquals(listOf("b2"), queue.cuesAt(s(6.0), 0, 0))
    }

    @Test
    fun readingAidHoldsAShortLineButNeverOverTheNext() {
        val queue = queue()
        // 40 characters for 0.5 s: at 17 characters a second it needs about 2.4 s.
        val line = "Das ist eine ziemlich lange Zeile hier!!"
        queue.add(s(10.0), listOf(line))
        queue.add(s(10.5), emptyList())
        queue.add(s(11.5), listOf("Nächste"))
        assertEquals(emptyList<String>(), queue.cuesAt(s(11.0), 0, 0))
        assertEquals(listOf(line), queue.cuesAt(s(11.0), 0, 17))
        assertEquals(listOf("Nächste"), queue.cuesAt(s(11.6), 0, 17))
    }

    @Test
    fun readingAidLetsTheLineGoAfterItsReadingTime() {
        val queue = queue()
        queue.add(s(10.0), listOf("Ja"))
        queue.add(s(10.2), emptyList())
        // "Ja": one line, so 1 s.
        assertEquals(listOf("Ja"), queue.cuesAt(s(10.9), 0, 17))
        assertEquals(emptyList<String>(), queue.cuesAt(s(11.1), 0, 17))
    }

    @Test
    fun oldGroupsGoButTheDelayRangeStays() {
        val queue = queue()
        for (i in 0 until 1000) queue.add(s(i.toDouble()), listOf("$i"))
        queue.cuesAt(s(999.0), 0, 0)
        // Ten minutes back are kept for a delay turned up.
        assertEquals(listOf("399"), queue.cuesAt(s(999.0), s(600.0), 0))
        assert(queue.size in 600..602)
    }
}
