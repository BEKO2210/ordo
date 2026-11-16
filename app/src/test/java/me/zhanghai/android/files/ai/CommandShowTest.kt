/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The "show" search and the filters a change shares with it. Nothing here may change a file. */
class CommandShowTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun file(path: String, size: Int = 10, daysAgo: Int = 0, text: String? = null) = File(root, path).apply {
        parentFile!!.mkdirs()
        writeText(text ?: "x".repeat(size))
        setLastModified(now - daysAgo * day)
    }

    @Before
    fun setUp() {
        root = temp.newFolder("0")
    }

    private fun show(plan: CommandPlan, vararg sources: File = arrayOf(root)) =
        CommandFiles.show(plan, sources.toList(), root, now).map { it.path.removePrefix(root.path + "/") }

    @Test
    fun searchesEveryFolderBelow_butNotHiddenOrAndroidData() {
        file("Movies/a.mp4")
        file("Download/deep/er/b.mp4")
        file(".hidden/c.mp4")
        file("Download/.d.mp4")
        file("Android/data/com.x/e.mp4")
        file("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/f.mp4")
        val found = show(CommandPlan("show", "", kinds = listOf("video"))).toSet()
        assertEquals(setOf("Movies/a.mp4", "Download/deep/er/b.mp4",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/f.mp4"), found)
    }

    @Test
    fun sortAndLimit() {
        file("a.mp4", size = 30, daysAgo = 5)
        file("b.mp4", size = 10, daysAgo = 1)
        file("c.mp4", size = 20, daysAgo = 3)
        assertEquals(listOf("a.mp4", "c.mp4"), show(CommandPlan("show", "", sort = "size", limit = 2)))
        assertEquals(listOf("b.mp4"), show(CommandPlan("show", "", sort = "newest", limit = 1)))
        // nothing asked: newest first, everything
        assertEquals(listOf("b.mp4", "c.mp4", "a.mp4"), show(CommandPlan("show", "")))
    }

    @Test
    fun newerThanAndToday() {
        file("old.jpg", daysAgo = 10)
        file("week.jpg", daysAgo = 3)
        assertEquals(listOf("week.jpg"), show(CommandPlan("show", "", newerThan = 1, ageUnit = "weeks")))
        // "today" counts from the real midnight, so these files get real times
        val real = System.currentTimeMillis()
        File(root, "old.jpg").setLastModified(real - 10 * day)
        File(root, "week.jpg").setLastModified(real - 3 * day)
        file("today.jpg").setLastModified(real)
        assertEquals(listOf("today.jpg"),
            CommandFiles.show(CommandPlan("show", "", today = true), listOf(root), root).map { it.name })
    }

    @Test
    fun wordIsFoundInNameAndPlainText_butPdfOnlyByName() {
        file("notes.txt", text = "Die Rechnung von Mediamarkt")
        file("Rechnung-2026.pdf")
        file("scan.pdf", text = "Rechnung Mediamarkt as raw bytes")
        file("other.txt", text = "nothing")
        assertEquals(setOf("notes.txt", "Rechnung-2026.pdf"),
            show(CommandPlan("show", "", contentContains = "rechnung")).toSet())
    }

    @Test
    fun aKindThatMeansNothing_findsNothing() {
        file("a.mp4")
        assertTrue(show(CommandPlan("show", "", kinds = listOf("hologram"))).isEmpty())
    }

    @Test
    fun showSources_namedPlacesPathsAndAsk() {
        File(root, "Pictures/Screenshots").mkdirs()
        File(root, "DCIM/Screenshots").mkdirs()
        File(root, "Download").mkdirs()
        File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images").mkdirs()
        assertEquals(listOf(root), CommandCheck.showSources("", root))
        assertEquals(2, CommandCheck.showSources("Screenshots", root)!!.size)
        assertEquals("WhatsApp Images", CommandCheck.showSources("WhatsApp Images", root)!!.single().name)
        assertEquals(File(root, "Download"), CommandCheck.showSources("Download", root)!!.single())
        assertEquals("Screenshots", CommandCheck.showSources("Pictures/Screenshots", root)!!.single().name)
        assertNull(CommandCheck.showSources("Nirgends", root))
        assertNull(CommandCheck.showSources("../..", root))
        assertNull(CommandCheck.showSources("/etc", root))
    }

    // A change (move, delete) uses the same filters: "heute", "letzte Woche" and a word must narrow it, never widen.
    @Test
    fun collectHonoursNewFilters() {
        val download = File(root, "Download")
        file("Download/old.png", daysAgo = 30)
        file("Download/new.png", daysAgo = 1)
        file("Download/insta_1.png", daysAgo = 30)
        val names = { plan: CommandPlan -> CommandFiles.collect(plan, download, now).map { it.name } }
        assertEquals(listOf("new.png"), names(CommandPlan("delete", "Download", newerThan = 1, ageUnit = "weeks")))
        assertEquals(listOf("insta_1.png"), names(CommandPlan("move", "Download", contentContains = "insta")))
        assertEquals(listOf("insta_1.png"), names(CommandPlan("move", "Download", sort = "size", limit = 1).copy(nameContains = "insta")))
    }

    @Test
    fun secondConfirm_countsTheNewFiltersAsFilters() {
        val top = File(root, "Download").apply { mkdirs() }
        assertTrue(CommandCheck.needsSecondConfirm(CommandPlan("delete", "Download"), top, root, 3))
        assertEquals(false, CommandCheck.needsSecondConfirm(CommandPlan("delete", "Download", today = true), top, root, 3))
        assertTrue(CommandCheck.needsSecondConfirm(CommandPlan("delete", "Download", today = true), top, root, 51))
    }

    @Test
    fun shippedDesignHasNoRulesAndNoShow() {
        val plan = CommandPlan("delete", "Download")
        assertNull(CommandDesign.Today.route("Was kann ich löschen?"))
        assertEquals(plan, CommandDesign.Today.fix("Was kann ich löschen?", plan, emptyList()))
        assertTrue("\"show\"" !in CommandDesign.Today.grammar)
    }
}
