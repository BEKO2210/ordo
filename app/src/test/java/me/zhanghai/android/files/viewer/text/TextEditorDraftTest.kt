/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.text

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

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
        assertNull(editorDraftToApply(viewStateKept = true, typedSinceLoad = false, draft = "neu"))
        assertEquals("neu", editorDraftToApply(viewStateKept = false, typedSinceLoad = false, draft = "neu"))
        assertNull(editorDraftToApply(viewStateKept = false, typedSinceLoad = false, draft = null))
    }

    @Test
    fun keystrokesAfterTheFileLoadsBeatALateDraft() {
        assertNull(editorDraftToApply(viewStateKept = false, typedSinceLoad = true, draft = "alt"))
    }

    @Test
    fun unreadableDraftIsMissingInsteadOfThrowing() {
        val dir = temp.newFolder("cache")
        val key = TextEditorDraft.key("x")
        TextEditorDraft.write(dir, key, "geheim")
        val path = java.io.File(dir, "text-editor-draft-$key").toPath()
        val previous = Files.getPosixFilePermissions(path)
        Files.setPosixFilePermissions(path, emptySet<PosixFilePermission>())
        try {
            assertNull(TextEditorDraft.read(dir, key))
        } finally {
            Files.setPosixFilePermissions(path, previous)
        }
    }

    @Test
    fun suspendedReadRunsOnTheGivenDispatcher() = runBlocking {
        val dir = temp.newFolder("cache")
        val key = TextEditorDraft.key("thread")
        TextEditorDraft.write(dir, key, "Hallo")
        val caller = Thread.currentThread()
        val executor = Executors.newSingleThreadExecutor()
        val io = executor.asCoroutineDispatcher()
        try {
            var reader: Thread? = null
            val marking = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    io.dispatch(context, Runnable {
                        reader = Thread.currentThread()
                        block.run()
                    })
                }
            }
            assertEquals("Hallo", TextEditorDraft.readSuspending(dir, key, marking))
            assertNotEquals(caller, reader)
        } finally {
            io.close()
        }
    }

    @Test
    fun typingDuringSaveStaysDirty() {
        assertTrue(savedTextIsCurrent("abc", "abc"))
        assertFalse(savedTextIsCurrent("abcd", "abc"))
        assertFalse(savedTextIsCurrent("ab", "abc"))
    }
}
