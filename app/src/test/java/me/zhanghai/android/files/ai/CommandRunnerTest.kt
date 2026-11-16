/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CommandRunnerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var download: File
    private lateinit var music: File
    private lateinit var bin: File

    // A tiny stand-in for the shared trash: files move into bin/, the handle is the trashed file.
    private fun runner() = CommandRunner(root, false,
        trash = { f -> File(bin, f.name).let { t -> if (f.renameTo(t)) CommandRunner.Trashed(f, t) else null } },
        untrash = { e -> (e.handle as File).renameTo(e.original) })

    @Before
    fun setUp() {
        root = temp.newFolder("0")
        download = File(root, "Download").apply { mkdirs() }
        music = File(root, "Music").apply { mkdirs() }
        bin = temp.newFolder("bin")
    }

    private fun file(dir: File, name: String, text: String = name) = File(dir, name).apply { writeText(text) }

    @Test
    fun move_thenUndo_restoresEverything() {
        val a = file(download, "a.mp3")
        val b = file(download, "b.mp3")
        val journal = runner().run(CommandPlan("move", "Download", "Music"), download, music, listOf(a, b), emptyMap(), CommandFiles.snapshot(listOf(a, b)))
        assertEquals(2, journal.done)
        assertFalse(a.exists())
        assertTrue(File(music, "a.mp3").exists())
        assertEquals(2, runner().undo(journal))
        assertTrue(a.exists() && b.exists())
        assertEquals(0, music.list()!!.size)
    }

    @Test
    fun undo_toleratesSecondPrecisionTimes() {
        val a = file(download, "a.mp3")
        val journal = runner().run(CommandPlan("move", "Download", "Music"), download, music, listOf(a), emptyMap(), CommandFiles.snapshot(listOf(a)))
        val moved = File(music, "a.mp3")
        // What shared storage on a phone does later: the same time, cut to whole seconds.
        moved.setLastModified(moved.lastModified() / 1000 * 1000)
        assertEquals(1, runner().undo(journal))
        assertTrue(a.exists())
    }

    @Test
    fun existingTarget_isSkippedAndNeverTouchedByUndo() {
        val a = file(download, "a.mp3", "new")
        val old = file(music, "a.mp3", "old")
        val journal = runner().run(CommandPlan("copy", "Download", "Music"), download, music, listOf(a), emptyMap(), CommandFiles.snapshot(listOf(a)))
        assertEquals(1, journal.skipped)
        runner().undo(journal)
        assertEquals("old", old.readText())
    }

    @Test
    fun copyUndo_leavesChangedCopyAlone() {
        val a = file(download, "a.txt")
        val journal = runner().run(CommandPlan("copy", "Download", "Music"), download, music, listOf(a), emptyMap(), CommandFiles.snapshot(listOf(a)))
        val copy = File(music, "a.txt")
        copy.writeText("edited by the user afterwards")
        assertEquals(0, runner().undo(journal))
        assertTrue(copy.exists())
        assertTrue(a.exists())
    }

    @Test
    fun fileReplacedByFolderAfterPreview_isSkipped() {
        val a = File(download, "a.mp3").apply { mkdirs() }
        val journal = runner().run(CommandPlan("move", "Download", "Music"), download, music, listOf(a), emptyMap(), CommandFiles.snapshot(listOf(a)))
        assertEquals(0, journal.done)
        assertEquals(1, journal.skipped)
    }

    @Test
    fun delete_goesToTrashAndComesBack() {
        val a = file(download, "a.pdf")
        val journal = runner().run(CommandPlan("delete", "Download"), download, null, listOf(a), emptyMap(), CommandFiles.snapshot(listOf(a)))
        assertFalse(a.exists())
        assertEquals(1, runner().undo(journal))
        assertTrue(a.exists())
    }

    @Test
    fun organize_sortsIntoKindFolders() {
        val a = file(download, "a.mp3")
        val journal = runner().run(CommandPlan("organize", "Download", groupBy = "type"), download, null,
            listOf(a), mapOf("audio" to "Audio"), CommandFiles.snapshot(listOf(a)))
        assertTrue(File(download, "Audio/a.mp3").exists())
        runner().undo(journal)
        assertTrue(a.exists())
    }

    // Phase 0 gap: a file replaced since the preview (same name, other size or time) is another file; never touch it.
    @Test
    fun replacedSinceThePreview_isSkippedAndCounted() {
        val a = file(download, "ticket.pdf", "old ticket")
        val b = file(download, "keep.pdf")
        val seen = CommandFiles.snapshot(listOf(a, b))
        a.writeText("a new ticket, bought later")
        val journal = runner().run(CommandPlan("delete", "Download"), download, null, listOf(a, b), emptyMap(), seen)
        assertTrue(a.exists())
        assertEquals("a new ticket, bought later", a.readText())
        assertEquals(1, journal.done)
        assertEquals(1, journal.changed)
        assertEquals(1, journal.skipped)
    }

    @Test
    fun sameSizeButNewerTime_isSkipped() {
        val a = file(download, "a.mp3")
        val seen = CommandFiles.snapshot(listOf(a))
        a.setLastModified(a.lastModified() + 60_000)
        val journal = runner().run(CommandPlan("move", "Download", "Music"), download, music, listOf(a), emptyMap(), seen)
        assertEquals(0, journal.done)
        assertEquals(1, journal.changed)
        assertTrue(a.exists())
    }

    @Test
    fun fileMissingFromThePreview_isSkipped() {
        val a = file(download, "a.mp3")
        val journal = runner().run(CommandPlan("move", "Download", "Music"), download, music, listOf(a), emptyMap(), emptyMap())
        assertEquals(0, journal.done)
        assertTrue(a.exists())
    }
}
