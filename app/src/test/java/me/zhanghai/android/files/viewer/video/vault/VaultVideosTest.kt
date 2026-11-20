/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Tresor video's player item carries no trace of its file name: the MediaSession shows only "Vault video". */
class VaultVideosTest {
    private val id = "1763590000000-ab12cd34"

    @Test
    fun opaqueUri() {
        assertEquals("ordo-vault:///$id", VaultVideos.uri(id))
        assertTrue(VaultVideos.isVault(VaultVideos.uri(id)))
        assertFalse(VaultVideos.isVault("file:///storage/emulated/0/Movies/a.mp4"))
    }

    private class FakeChannel : java.io.Closeable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    @Test
    fun revokeClosesOpenChannelsAndOnesOpenedDuringIt() {
        val channels = OpenChannels()
        val a = FakeChannel()
        val b = FakeChannel()
        val done = FakeChannel()
        assertTrue(channels.add(a, channels.generation()))
        assertTrue(channels.add(b, channels.generation()))
        assertTrue(channels.add(done, channels.generation()))
        channels.remove(done)
        // An open that read the generation before the revoke but registers after it.
        val late = FakeChannel()
        val lateGeneration = channels.generation()
        channels.revoke()
        assertTrue(a.closed && b.closed)
        assertFalse(done.closed)
        assertEquals(0, channels.count())
        assertFalse(channels.add(late, lateGeneration))
        assertTrue(late.closed)
        // A new grant after the revoke reads again.
        val next = FakeChannel()
        assertTrue(channels.add(next, channels.generation()))
        assertFalse(next.closed)
    }

    @Test
    fun itemHasOnlyIdAndGenericTitle() {
        val item = VaultVideos.item(id, "video/mp4", "Tresor-Video").build()
        assertEquals("ordo-vault:///$id", item.mediaId)
        val m = item.mediaMetadata
        assertEquals("Tresor-Video", m.title.toString())
        assertEquals("Tresor-Video", m.displayTitle.toString())
        // Every other field a session, lock screen, notification listener or AVRCP could read stays empty.
        listOf(
            m.artist, m.albumTitle, m.albumArtist, m.subtitle, m.description, m.writer, m.composer, m.conductor,
            m.genre, m.compilation, m.station, m.artworkData, m.artworkUri, m.extras, m.trackNumber, m.recordingYear
        ).forEach { assertNull(it) }
    }
}
