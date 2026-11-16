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
        assertTrue("the release switch ships off", !CommandDesign.RELEASE_GUARDED)
        assertNull(CommandDesign.Today.decide("x", "{}", folders, now) { 0 })
        assertEquals(42_485_408L, CommandDesign.Tuned.adapter.size)
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
    }
}
