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
