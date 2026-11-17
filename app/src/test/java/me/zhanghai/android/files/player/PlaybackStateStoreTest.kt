/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackStateStoreTest {
    @Test
    fun queueRoundTrips() {
        val paths = listOf("file:///Music/One.mp3", "smb://server/Music/Two.ogg")
        assertEquals(paths, PlaybackStateStore.decodeQueue(PlaybackStateStore.encodeQueue(paths)))
    }

    @Test
    fun positionAndModesRoundTrip() {
        assertEquals(1 to 42_000L, PlaybackStateStore.decodePosition(PlaybackStateStore.encodePosition(1, 42_000L)))
        val modes = PlaybackStateStore.Modes(true, Player.REPEAT_MODE_ALL)
        assertEquals(modes, PlaybackStateStore.decodeModes(PlaybackStateStore.encodeModes(modes)))
    }

    @Test
    fun corruptOrEmptyStateIsIgnored() {
        assertNull(PlaybackStateStore.decodeQueue(null))
        assertNull(PlaybackStateStore.decodeQueue("not json"))
        assertNull(PlaybackStateStore.decodeQueue("[]"))
        assertEquals(0 to 0L, PlaybackStateStore.decodePosition("not json"))
        assertEquals(PlaybackStateStore.Modes(), PlaybackStateStore.decodeModes("not json"))
    }
}
