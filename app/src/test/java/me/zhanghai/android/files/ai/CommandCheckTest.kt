/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import me.zhanghai.android.files.ai.CommandCheck.Outcome
import me.zhanghai.android.files.ai.CommandCheck.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CommandCheckTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File

    @Before
    fun setUp() {
        root = temp.newFolder("0")
        listOf("Download", "Music", "DCIM/Camera", "Android/data", ".OrdoTrash", "Projekte/2026/Fertig")
            .forEach { File(root, it).mkdirs() }
    }

    private fun check(text: String, plan: CommandPlan, here: File? = null) =
        CommandCheck.check(text, plan, here, root)

    @Test
    fun move_resolvesAliases() {
        val outcome = check(
            "verschiebe alle mp3 von Downloads nach Musik",
            CommandPlan("move", "Downloads", "Musik", extensions = listOf("mp3"))
        )
        assertEquals(Outcome.Ready(outcome.plan(), File(root, "Download"), File(root, "Music"), false), outcome)
    }

    @Test
    fun move_unknownDestinationIsCreated() {
        val outcome = check("move pdfs from Download to Steuern", CommandPlan("move", "Download", "Steuern"))
        assertEquals(Outcome.Ready(outcome.plan(), File(root, "Download"), File(root, "Steuern"), true), outcome)
    }

    @Test
    fun delete_needsDeleteWord() {
        val plan = CommandPlan("delete", "Download")
        assertEquals(Outcome.Refused(Reason.UNSURE_DELETE), check("verschiebe alles älter als ein Jahr", plan))
        assertTrue(check("lösche alles in Download", plan) is Outcome.Ready)
        assertTrue(check("Remove old stuff from Download", plan) is Outcome.Ready)
    }

    @Test
    fun none_isRefused() {
        assertEquals(Outcome.Refused(Reason.UNSUPPORTED), check("Formatiere die SD-Karte", CommandPlan("none", "")))
        assertEquals(Outcome.Refused(Reason.UNSUPPORTED), check("x", CommandPlan("format", "Download")))
    }

    @Test
    fun hereWithoutFolder_asks() {
        assertEquals(
            Outcome.AskFolder(CommandPlan("delete", "."), forDestination = false),
            check("lösche hier alle Videos", CommandPlan("delete", "."))
        )
        val here = File(root, "Download")
        assertTrue(check("lösche hier alle Videos", CommandPlan("delete", "."), here) is Outcome.Ready)
        assertEquals(
            Outcome.AskFolder(CommandPlan("move", "Download", ""), forDestination = true),
            check("verschiebe aus Download", CommandPlan("move", "Download", ""))
        )
    }

    @Test
    fun unknownSource_asks() {
        assertTrue(check("lösche alles in Gibtsnicht", CommandPlan("delete", "Gibtsnicht")) is Outcome.AskFolder)
    }

    @Test
    fun protectedFolders_refused() {
        val refused = Outcome.Refused(Reason.PROTECTED_FOLDER)
        assertEquals(refused, check("lösche alles in data", CommandPlan("delete", "/x/Android/data")
            .copy(source = File(root, "Android/data").path)))
        assertEquals(refused, check("delete all in OrdoTrash", CommandPlan("delete", File(root, ".OrdoTrash").path)))
        assertEquals(refused, check("verschiebe nach Download", CommandPlan("move", "Download", "Download")))
        assertTrue(CommandCheck.isProtected(File(root, "android/data"), root, false))
        assertTrue(CommandCheck.isProtected(File(root, "Music/../Android/data"), root, false))
        assertTrue(CommandCheck.isProtected(File(root, "dcim/CAMERA"), root, false))
        assertTrue(CommandCheck.isProtected(File(root, "../x"), root, false))
    }

    @Test
    fun cameraOnlyWhenNamed() {
        val camera = File(root, "DCIM/Camera").path
        assertEquals(
            Outcome.Refused(Reason.PROTECTED_FOLDER),
            check("lösche alle Videos hier", CommandPlan("delete", "."), File(camera))
        )
        assertTrue(check("lösche alte Videos aus der Kamera", CommandPlan("delete", "Kamera")) is Outcome.Ready)
    }

    @Test
    fun resolve_findsNestedAndIgnoresHidden() {
        assertEquals(File(root, "Projekte/2026/Fertig"), CommandCheck.resolve("Ordner fertig", null, root))
        assertNull(CommandCheck.resolve("OrdoTrash", null, root))
        assertNull(CommandCheck.resolve("/etc", null, root))
    }

    @Test
    fun files_filterByKindAgeSize() {
        val dir = File(root, "Download")
        val now = 100L * 86_400_000L
        fun file(name: String, ageDays: Int, bytes: Int) = File(dir, name).apply {
            writeBytes(ByteArray(bytes)); setLastModified(now - ageDays * 86_400_000L)
        }
        val song = file("song.MP3", 40, 10)
        file("new.mp3", 1, 10)
        file("notes.txt", 40, 10)
        file(".hidden.mp3", 40, 10)
        assertEquals(listOf(song), CommandFiles.collect(CommandPlan("delete", "Download", kinds = listOf("audio"),
            olderThan = 30), dir, now))
        val big = file("big.mkv", 0, 2_000_001)
        assertEquals(listOf(big), CommandFiles.collect(CommandPlan("delete", "Download", largerThan = 2), dir, now))
        assertEquals(listOf(song), CommandFiles.collect(CommandPlan("delete", "x", nameContains = "SONG"), dir, now))
        val wav = file("take.wav", 40, 10)
        assertEquals(listOf(song), CommandFiles.collect(CommandPlan("move", "x", kinds = listOf("audio"),
            extensions = listOf("mp3"), olderThan = 30), dir, now))
        assertEquals(listOf(song, wav), CommandFiles.collect(CommandPlan("move", "x", kinds = listOf("audio"),
            olderThan = 30), dir, now))
    }

    @Test
    fun units() {
        assertEquals(14, CommandPlan("delete", "x", olderThan = 2, ageUnit = "weeks").olderThanDays)
        assertEquals(3_000_000_000L, CommandPlan("delete", "x", largerThan = 3, sizeUnit = "gb").largerThanBytes)
        assertEquals(listOf("mp3"), CommandPlan("move", "x", extensions = listOf(".MP3", " ")).cleanExtensions)
    }

    @Test
    fun deleteWordInFileNameDoesNotCount() {
        assertEquals(Outcome.Refused(Reason.UNSURE_DELETE),
            check("copy delete-me.txt to Music", CommandPlan("delete", "Download")))
        assertEquals(Outcome.Refused(Reason.UNSURE_DELETE),
            check("kopiere alles aus Download", CommandPlan("delete", "Download", nameContains = "löschen")))
    }

    @Test
    fun cameraInFileNameDoesNotUnlockCamera() {
        assertEquals(Outcome.Refused(Reason.PROTECTED_FOLDER),
            check("lösche camera_backup.zip", CommandPlan("delete", "Kamera")))
    }

    @Test
    fun ambiguousFolder_asksWithCandidates() {
        File(root, "Alarms/Receipts").mkdirs()
        File(root, "Documents/Receipts").mkdirs()
        val outcome = check("lösche alles in Receipts", CommandPlan("delete", "Receipts"))
        assertEquals(listOf(File(root, "Alarms/Receipts"), File(root, "Documents/Receipts")),
            (outcome as Outcome.AskFolder).candidates)
    }

    @Test
    fun invalidFilterSelectsNothing_hugeAgeIsCapped() {
        val dir = File(root, "Download").apply { File(this, "a.txt").writeText("x") }
        assertEquals(emptyList<File>(), CommandFiles.collect(CommandPlan("delete", "x", extensions = listOf(".")), dir))
        assertEquals(36_500, CommandPlan("delete", "x", olderThan = Int.MAX_VALUE, ageUnit = "years").olderThanDays)
    }

    private fun Outcome.plan(): CommandPlan = (this as Outcome.Ready).plan
}
