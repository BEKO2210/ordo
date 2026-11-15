/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import me.zhanghai.android.files.ai.CommandCheck.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * "Lösche die doppelten Dateien im Download": the on-device model (Qwen3.5-4B Q4_0, ordo-memory-lab 15.11.2026)
 * answers with the whole Download folder, because the plan has no field for duplicates. The app must narrow it.
 */
class CommandDuplicatesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var download: File

    // Exactly what the model planned in the benchmark: delete, Download, no filter at all.
    private val modelPlan = CommandPlan("delete", "Download")

    @Before
    fun setUp() {
        root = temp.newFolder("0")
        download = File(root, "Download").apply { mkdirs() }
        file("Rechnung.pdf", "invoice", 1_000_000)
        file("Rechnung (1).pdf", "invoice", 2_000_000)
        file("setup.apk", "apk", 5_000_000)
        file("setup (1).apk", "apk", 5_000_000)
        file("setup (2).apk", "apk", 6_000_000)
        file("Foto.jpg", "photo", 1_000_000)
        // Same size as Foto.jpg, other content: not a duplicate.
        file("Scan.jpg", "phot0", 1_000_000)
    }

    private fun file(name: String, content: String, modified: Long) =
        File(download, name).apply { writeText(content); setLastModified(modified) }

    private fun names(files: List<File>) = files.map { it.name }

    @Test
    fun duplicateWording_narrowsTheWholeFolderPlanToExtraCopies() {
        val outcome = CommandCheck.check("Lösche die doppelten Dateien im Download", modelPlan, null, root)
        outcome as Outcome.Ready
        assertTrue(outcome.plan.duplicates)
        val files = CommandFiles.collect(outcome.plan, outcome.source)
        // The oldest of each group stays; on equal time the shorter name is the original.
        assertEquals(listOf("Rechnung (1).pdf", "setup (1).apk", "setup (2).apk"), names(files))
        assertFalse(CommandCheck.needsSecondConfirm(outcome.plan, outcome.source, root, files.size))
    }

    @Test
    fun otherDuplicateWords_narrowToo() {
        listOf("Lösch die doppelt heruntergeladenen Sachen im Download", "Entferne die Duplikate aus Download",
            "delete the duplicates in Download", "remove duplicate files from downloads",
            "Lösche mehrfach geladene Dateien im Download").forEach { text ->
            val outcome = CommandCheck.check(text, modelPlan, null, root)
            assertTrue(text, (outcome as Outcome.Ready).plan.duplicates)
        }
    }

    @Test
    fun duplicatesWithoutAFolder_askForTheFolderAndStayNarrowed() {
        val text = "Lösch die doppelt heruntergeladenen Sachen"
        assertTrue(CommandCheck.check(text, CommandPlan("delete", "."), null, root) is Outcome.AskFolder)
        // The user picks the folder; the sheet checks the same sentence again.
        val outcome = CommandCheck.check(text, CommandPlan("delete", download.path), null, root) as Outcome.Ready
        assertEquals(3, CommandFiles.collect(outcome.plan, outcome.source).size)
    }

    @Test
    fun folderWithoutDuplicates_selectsNothing() {
        val clean = File(root, "Documents").apply { mkdirs() }
        File(clean, "a.txt").writeText("a")
        File(clean, "b.txt").writeText("b")
        val outcome = CommandCheck.check("Lösche die Duplikate in Documents", CommandPlan("delete", "Documents"),
            null, root) as Outcome.Ready
        assertTrue(CommandFiles.collect(outcome.plan, outcome.source).isEmpty())
    }

    @Test
    fun noDuplicateWord_noNarrowing() {
        val delete = CommandCheck.check("Lösche alles im Download", modelPlan, null, root)
        assertFalse((delete as Outcome.Ready).plan.duplicates)
        // "Kopiere" must not read as "Kopie" (copy of a file).
        val copy = CommandCheck.check("Kopiere alle Dateien aus Download nach Backup",
            CommandPlan("copy", "Download", "Backup"), null, root)
        assertFalse((copy as Outcome.Ready).plan.duplicates)
    }

    @Test
    fun wholeTopLevelFolderOrManyFiles_needASecondConfirmation() {
        val all = CommandFiles.collect(modelPlan, download)
        assertEquals(7, all.size)
        assertTrue(CommandCheck.needsSecondConfirm(modelPlan, download, root, all.size))
        assertTrue(CommandCheck.needsSecondConfirm(CommandPlan("delete", "."), root, root, 1))
        // A filter, a subfolder or a move keep the single confirmation...
        assertFalse(CommandCheck.needsSecondConfirm(modelPlan.copy(extensions = listOf("apk")), download, root, 3))
        val nested = File(download, "Alt").apply { mkdirs() }
        assertFalse(CommandCheck.needsSecondConfirm(modelPlan, nested, root, 10))
        assertFalse(CommandCheck.needsSecondConfirm(modelPlan.copy(action = "move"), download, root, 500))
        // ...unless the delete takes more than MANY_FILES files.
        assertTrue(CommandCheck.needsSecondConfirm(modelPlan.copy(extensions = listOf("apk")), nested, root,
            CommandCheck.MANY_FILES + 1))
    }
}
