/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import me.zhanghai.android.files.player.PlayHistoryStore.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayHistoryStoreTest {
    @Test
    fun aPlayIsCountedAndMovesToTheFront() {
        var entries = PlayHistoryStore.record(emptyList(), "file:///a.mp3", 10)
        entries = PlayHistoryStore.record(entries, "file:///b.mp3", 20)
        entries = PlayHistoryStore.record(entries, "file:///a.mp3", 30)
        assertEquals(listOf(Entry("file:///a.mp3", 2, 30), Entry("file:///b.mp3", 1, 20)), entries)
    }

    @Test
    fun theOldestEntriesGoBeyondTheLimit() {
        var entries = emptyList<Entry>()
        for (index in 1..5) entries = PlayHistoryStore.record(entries, "file:///$index.mp3", index.toLong(), 3)
        assertEquals(listOf("file:///5.mp3", "file:///4.mp3", "file:///3.mp3"), entries.map { it.pathUri })
    }

    @Test
    fun recentAndMostPlayedOrder() {
        val entries = listOf(Entry("a", 1, 50), Entry("b", 4, 10), Entry("c", 4, 30), Entry("d", 2, 40))
        assertEquals(listOf("a", "d", "c", "b"), PlayHistoryStore.recent(entries).map { it.pathUri })
        assertEquals(listOf("c", "b", "d", "a"), PlayHistoryStore.mostPlayed(entries).map { it.pathUri })
    }

    @Test
    fun encodingRoundTripsAndBadDataIsIgnored() {
        val entries = listOf(Entry("file:///a%20b.mp3", 3, 123), Entry("file:///c.mp3", 1, 7))
        assertEquals(entries, PlayHistoryStore.decode(PlayHistoryStore.encode(entries)))
        assertTrue(PlayHistoryStore.decode("not json").isEmpty())
        assertTrue(PlayHistoryStore.decode(null).isEmpty())
        assertEquals(listOf(Entry("x", 1, 0)), PlayHistoryStore.decode("""[{"p":"x"},{"n":2},{"p":""}]"""))
    }
}
