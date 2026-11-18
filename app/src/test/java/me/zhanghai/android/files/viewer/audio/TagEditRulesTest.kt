/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class TagEditRulesTest {
    @Test
    fun androidSevenOnlyShows() {
        // API 24 and 25: the editor opens with the note, whatever the file.
        assertEquals(EditBlock.ANDROID, TagEditRules.editBlock(24, listOf("mp3"), true))
        assertEquals(EditBlock.ANDROID, TagEditRules.editBlock(25, listOf("opus"), false))
        assertEquals(EditBlock.NONE, TagEditRules.editBlock(26, listOf("mp3"), true))
    }

    @Test
    fun opusIsReadOnlyEvenOnWritableStorage() {
        assertEquals(EditBlock.FORMAT, TagEditRules.editBlock(36, listOf("opus"), true))
        assertEquals(EditBlock.FORMAT, TagEditRules.editBlock(36, listOf("mp3", "wav"), true))
    }

    @Test
    fun storageComesLast() {
        assertEquals(EditBlock.STORAGE, TagEditRules.editBlock(36, listOf("flac", "M4A"), false))
        assertEquals(EditBlock.NONE, TagEditRules.editBlock(36, listOf("flac", "ogg", "m4a"), true))
    }
}
