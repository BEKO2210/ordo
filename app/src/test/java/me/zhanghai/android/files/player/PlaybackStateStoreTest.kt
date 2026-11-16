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
    fun roundTripPreservesQueueAndPosition() {
        val state = PlaybackStateStore.State(
            listOf("file:///Music/One.mp3", "smb://server/Music/Two.ogg"),
            1,
            42_000L,
            true,
            Player.REPEAT_MODE_ALL
        )
        assertEquals(state, PlaybackStateStore.decode(PlaybackStateStore.encode(state)))
    }

    @Test
    fun corruptOrEmptyStateIsIgnored() {
        assertNull(PlaybackStateStore.decode(null))
        assertNull(PlaybackStateStore.decode("not json"))
        assertNull(PlaybackStateStore.decode("{\"paths\":[]}"))
    }
}
