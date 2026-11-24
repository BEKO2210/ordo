/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.coil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SongCoverKeyTest {
    @Test
    fun keyNeedsNoFile() {
        val key = SongCoverKeyer.key(SongCover("/no/such/file.mp3", null, 42))
        assertEquals("song:/no/such/file.mp3:42:0", key)
    }

    @Test
    fun changedFileGetsNewKey() {
        assertNotEquals(
            SongCoverKeyer.key(SongCover("/a.mp3", null, 1)),
            SongCoverKeyer.key(SongCover("/a.mp3", null, 2))
        )
    }

    @Test
    fun editWithinTheSameSecondGetsNewKeyBySize() {
        assertNotEquals(
            SongCoverKeyer.key(SongCover("/a.mp3", null, 5_000, 1_000)),
            SongCoverKeyer.key(SongCover("/a.mp3", null, 5_000, 1_200))
        )
    }
}
