/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

/** The net's decision in the app's terms, and the executor for the fields only the net sets. */
class CommandNetPlanTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val now = LocalDateTime.of(2026, 11, 15, 12, 0)
    private val folders = listOf("Download", "Documents")

    @Test
    fun guardedPlanBecomesTheAppPlan() {
        val outcome = CommandNetPlan.guarded(
            "Verschiebe die Telekom PDFs aus Download nach Documents",
            """{"action":"move","source":"Download","destination":"Documents","name_contains":"Telekom"}""", folders, now
        ) { 1 }
        val plan = (outcome as NetOutcome.Plan).plan
        assertEquals("move", plan.action)
        assertEquals("Download", plan.source)
        assertEquals("Documents", plan.destination)
        assertEquals(listOf("pdf"), plan.extensions)
        assertEquals(listOf("telekom"), plan.textTerms)
    }

    @Test
    fun vagueChangeAsksBackAndCannotDoIsRefusedBeforeTheModel() {
        assertEquals(NetOutcome.Ask("vague_time", listOf("alten")),
            CommandNetPlan.guarded("Verschieb die alten Sachen", """{"action":"move"}""", folders, now) { 0 })
        assertEquals(NetOutcome.Refuse("archive"),
            CommandNetPlan.guarded("Pack alle Fotos in eine ZIP Datei", null, folders, now) { 0 })
        // no refusal before the model: the sheet asks the model
        assertEquals("none", (CommandNetPlan.guarded("Zeig mir die Fotos", null, folders, now) { 0 } as NetOutcome.Plan).plan.action)
    }

    @Test
    fun anAnswerThatIsNoJsonIsNone() {
        assertNull(CommandNetPlan.raw("{not json"))
        val outcome = CommandNetPlan.tuned("Zeig mir die Fotos", "{not json", folders, now)
        assertEquals("none", (outcome as NetOutcome.Plan).plan.action)
    }

    @Test
    fun releaseBuildsKeepTodayUntilTheSwitch() {
        // release: the preview setting decides (off by default); debug: DebugAiActivity may override it
        assertEquals(CommandDesign.Today, CommandDesign.select(preview = false, pick = null, showKr = false))
        assertEquals(CommandDesign.Guarded, CommandDesign.select(preview = true, pick = null, showKr = false))
        assertEquals(CommandDesign.Today, CommandDesign.select(preview = true, pick = "today", showKr = false))
        assertEquals(CommandDesign.Tuned, CommandDesign.select(preview = false, pick = "tuned", showKr = false))
        assertEquals(CommandDesign.KR, CommandDesign.select(preview = true, pick = null, showKr = true))
        assertNull(CommandDesign.Today.decide("x", "{}", folders, now) { 0 })
        assertEquals(42_485_408L, CommandDesign.Tuned.adapter.size)
    }

    /** Real model answers from the emulator run: a delete without a filter in the raw plan gets "apk" from the words. */
    @Test
    fun deleteCommandsSelectExactlyWhatWasSaid() {
        val root = tmp.newFolder("phone")
        val download = File(root, "Download").apply { mkdirs() }
        listOf("spiel.apk", "app2.apk", "handbuch.pdf", "foto.jpg", "film.mp4", "klein.mp4", "text.txt")
            .forEach { File(download, it).writeText(it) }
        fun decide(text: String, answer: String) =
            (CommandNetPlan.guarded(text, answer, listOf("Download"), now, CommandNetCount(root, text)::count) as NetOutcome.Plan).plan
        fun files(plan: CommandPlan) = CommandFiles.collect(plan, download).map { it.name }.sorted()
        val apks = decide("Löschen alle APKs in Download", """{"action":"delete","source":"Download"}""")
        assertEquals(listOf("apk"), apks.extensions)
        assertEquals(listOf("app2.apk", "spiel.apk"), files(apks))
        val english = decide("Delete the APK files in Download", """{"action":"delete","source":"Download","name_contains":"APK"}""")
        assertEquals(listOf("app2.apk", "spiel.apk"), files(english))
        val all = decide("Löschen alles im Download Ordner", """{"action":"delete","source":"Download"}""")
        assertEquals(7, files(all).size)
        // a whole top-level folder without a filter: the second confirmation, as before
        assertTrue(CommandCheck.needsSecondConfirm(all, download, root, 7))
        assertTrue(!CommandCheck.needsSecondConfirm(apks, download, root, 2))
    }

    @Test
    fun filterWordsAreShownAsTyped() {
        val text = "Lösch die Steuererklärung vom Straßenfest, die Rechnug und den Vertrag, nicht den alten Vertrag"
        assertEquals("Steuererklärung", CommandNetPlan.typed(text, CommandNet.stem("steuererklärung")))
        assertEquals("Straßenfest", CommandNetPlan.typed(text, CommandNet.stem("straßenfest")))
        assertEquals("Rechnug", CommandNetPlan.typed(text, CommandNet.stem("rechnug")))   // a typo stays the user's word
        assertEquals("Vertrag", CommandNetPlan.typed(text, CommandNet.stem("vertrag")))   // twice: the first
        // a compound: the part that is the word, never the whole compound (that would read narrower than the filter)
        assertEquals("Bewerbung", CommandNetPlan.typed("zeig die Bewerbungsfotos", "bewerbung"))
        assertEquals("Rechnung", CommandNetPlan.typed("lösch die Handyrechnung", CommandNet.stem("rechnung")))
        assertEquals("Rechnungen", CommandNetPlan.typed("zeig alle Stromrechnungen", CommandNet.stem("rechnungen")))
        assertEquals("Rechnung", CommandNetPlan.typed("die Rechnung und noch eine Rechnung", "rechnung"))   // exact, twice
        assertEquals("xyz", CommandNetPlan.typed(text, "xyz"))
    }

    /** Three text words, one file: read and folded once; the budget is charged once per file, as in the count. */
    @Test
    fun aFileIsReadOnceForAllItsWords() {
        val dir = tmp.newFolder("text")
        File(dir, "a.txt").writeText("Steuer Versicherung Telekom")
        val plan = CommandPlan("show", "", textTerms = listOf("steur", "versicherung", "telekom"))
        val before = CommandFiles.textReads.get()
        assertEquals(listOf("a.txt"), CommandFiles.collect(plan, dir).map { it.name })
        assertEquals(1, CommandFiles.textReads.get() - before)
    }

    /** 4 x 18 MB of text with three words: within the 200 MB budget per file, over it if charged per word. */
    @Test
    fun previewAndCountAgreeOnTheTextBudget() {
        val dir = tmp.newFolder("budget")
        val body = ("Steuer Versicherung Telekom " + "x".repeat(1000) + "\n").repeat(18_000).toByteArray()
        repeat(4) { File(dir, "n$it.txt").writeBytes(body) }
        val plan = CommandPlan("delete", dir.path, textTerms = listOf("steur", "versicherung", "telekom"))
        assertEquals(4, CommandFiles.collect(plan, dir).size)
        assertEquals(4, CommandNetCount(dir.parentFile!!, "").count(plan))
    }

    @Test
    fun notFoundOnlyForAnAddOn() {
        val adapter = CommandDesign.Tuned.adapter
        assertTrue(CommandModel.failed(adapter, 404).notFound)
        // the model keeps today's error text, whatever the server answers
        assertTrue(!CommandModel.failed(CommandModel.parts(CommandDesign.Today).single(), 404).notFound)
        assertTrue(!CommandModel.failed(adapter, 500).notFound)
    }

    @Test
    fun limitChipNamesTheSort() {
        assertEquals(me.zhanghai.android.files.R.plurals.ai_filter_limit_oldest, CommandSheetFragment.limitPlurals("oldest"))
        assertEquals(me.zhanghai.android.files.R.plurals.ai_filter_limit_smallest, CommandSheetFragment.limitPlurals("smallest"))
        assertEquals(me.zhanghai.android.files.R.plurals.ai_filter_limit_size, CommandSheetFragment.limitPlurals("size"))
        assertEquals(me.zhanghai.android.files.R.plurals.ai_filter_limit_newest, CommandSheetFragment.limitPlurals("newest"))
        assertEquals(me.zhanghai.android.files.R.plurals.ai_filter_limit_newest, CommandSheetFragment.limitPlurals(""))
        // and the words say it, in both languages
        val res = File(System.getProperty("user.dir")!!).let { if (File(it, "src").isDirectory) it else File(it, "app") }
        for ((dir, words) in listOf("values" to listOf("largest", "oldest", "smallest", "newest"),
                "values-de" to listOf("größte", "älteste", "kleinste", "neueste"))) {
            val xml = File(res, "src/main/res/$dir/ai_strings.xml").readText()
            for ((sort, word) in listOf("size", "oldest", "smallest", "newest").zip(words)) {
                val name = if (sort == "size") "size" else sort
                val block = Regex("<plurals name=\"ai_filter_limit_$name\">(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL)
                    .find(xml)!!.groupValues[1]
                assertTrue("$dir $sort: $block", block.contains(word))
            }
        }
    }

    @Test
    fun goldenCounterReadsTextLikeTheApp() {
        assertEquals(CommandFiles.TEXT_EXTENSIONS, CommandNetGolden.LabPhone.TEXT_EXTENSIONS)
    }

    /** A show whose four words find nothing together: rules2 counts every subset (15), the folders are read once. */
    @Test
    fun eachFolderIsListedOncePerDecision() {
        val root = tmp.newFolder("root")
        File(root, "Download").mkdirs()
        File(root, "Documents/Arbeit").mkdirs()
        File(root, "Download/telekom_rechnung.pdf").writeText("x")
        File(root, "Documents/Arbeit/vertrag.txt").writeText("steuer")
        val counter = CommandNetCount(root, "zeig mir telekom rechnung vertrag steuer")
        val outcome = CommandNetPlan.guarded("zeig mir telekom rechnung vertrag steuer", """{"action":"show"}""",
            listOf("Download", "Documents", "Documents/Arbeit"), now, counter::count)
        assertTrue(outcome.toString(), outcome is NetOutcome.Plan)
        assertEquals("root, Download, Documents, Documents/Arbeit: each read once", 4, counter.listings)
        // a change reads its one folder once
        val change = CommandNetCount(root, "verschieb die telekom rechnung vertrag aus download nach documents")
        CommandNetPlan.guarded("verschieb die telekom rechnung vertrag aus download nach documents",
            """{"action":"move","source":"Download","destination":"Documents"}""", listOf("Download", "Documents"), now,
            change::count)
        assertEquals(1, change.listings)
    }

    @Test
    fun aSubsetSearchOver50000FilesIsQuick() {
        val root = tmp.newFolder("big")
        for (d in 0 until 50) {
            val dir = File(root, "Ordner$d").apply { mkdirs() }
            for (f in 0 until 1000) File(dir, "datei_${d}_$f.txt").createNewFile()
        }
        val text = "zeig mir telekom rechnung vertrag steuer versicherung"
        val counter = CommandNetCount(root, text)
        val started = System.nanoTime()
        val outcome = CommandNetPlan.guarded(text, """{"action":"show"}""", (0 until 50).map { "Ordner$it" }, now, counter::count)
        val ms = (System.nanoTime() - started) / 1_000_000
        println("subset search over 50,000 files: $ms ms, ${counter.listings} listings, $outcome")
        assertEquals(51, counter.listings)
        assertTrue("took $ms ms", ms < 60_000)
    }

    @Test
    fun termsAreFoldedAndSortsWork() {
        val dir = tmp.newFolder("d")
        fun file(name: String, bytes: Int, time: Long) = File(dir, name).apply { writeBytes(ByteArray(bytes)); setLastModified(time) }
        file("München_Urlaub.jpg", 30, 3_000_000L)
        file("Muenchen_Plan.pdf", 10, 1_000_000L)
        file("Berlin.jpg", 20, 2_000_000L)
        fun names(plan: CommandPlan) = CommandFiles.collect(plan, dir).map { it.name }
        assertEquals(listOf("Muenchen_Plan.pdf", "München_Urlaub.jpg"),
            names(CommandPlan("show", "", nameTerms = listOf("munchen"), sort = "oldest")))
        assertEquals(listOf("Berlin.jpg"), names(CommandPlan("show", "", excludeTerms = listOf("munchen"))))
        assertEquals(listOf("Muenchen_Plan.pdf", "Berlin.jpg", "München_Urlaub.jpg"), names(CommandPlan("show", "", sort = "smallest")))
        // the net's stems are folded ("Steuer" -> "steur"); the file's text is folded the same way
        File(dir, "notiz.txt").apply { writeText("Steuer 2025 Belege"); setLastModified(4_000_000L) }
        assertEquals(listOf("notiz.txt"), names(CommandPlan("show", "", textTerms = listOf(CommandNet.stem("Steuer")))))

    }
}
