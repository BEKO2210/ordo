/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextEditorDraftTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun draftSurvivesANewProcessAndIsRemovedAfterSave() {
        val dir = temp.newFolder("cache")
        val key = TextEditorDraft.key("/storage/emulated/0/note.txt")
        TextEditorDraft.write(dir, key, "Hallo ä")
        assertEquals("Hallo ä", TextEditorDraft.read(dir, key))
        TextEditorDraft.delete(dir, key)
        assertNull(TextEditorDraft.read(dir, key))
    }

    @Test
    fun missingDraftIsAbsent() {
        assertNull(TextEditorDraft.read(temp.newFolder("cache"), TextEditorDraft.key("x")))
    }

    @Test
    fun keyIsStableAndHasNoPathSeparators() {
        assertEquals(TextEditorDraft.key("/a/b"), TextEditorDraft.key("/a/b"))
        assertFalse(TextEditorDraft.key("/a/b").contains("/"))
        assertTrue(TextEditorDraft.key("/a/b") != TextEditorDraft.key("/a/c"))
    }

    @Test
    fun rotationKeepsTheViewStateAndDropsTheDraft() {
        assertNull(editorDraftToApply(viewStateKept = true, draft = "neu"))
        assertEquals("neu", editorDraftToApply(viewStateKept = false, draft = "neu"))
        assertNull(editorDraftToApply(viewStateKept = false, draft = null))
    }

    @Test
    fun typingDuringSaveStaysDirty() {
        assertTrue(savedTextIsCurrent("abc", "abc"))
        assertFalse(savedTextIsCurrent("abcd", "abc"))
        assertFalse(savedTextIsCurrent("ab", "abc"))
    }
}
