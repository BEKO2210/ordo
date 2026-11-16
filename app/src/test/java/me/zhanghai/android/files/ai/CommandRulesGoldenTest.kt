/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * The Kotlin rules against rules.py: every lab case (Dev, 20 originals, new holdout, regression) for each model
 * variant the lab scored with rules, raw model plan in, the Python result out. Fixture: tools/memory-lab/export_golden.py.
 * A difference is a bug in the port, never a reason to change the fixture.
 */
class CommandRulesGoldenTest {
    private val golden = JSONObject(javaClass.getResource("/ai/rules-golden.json")!!.readText())
    private val folders = golden.getJSONArray("folders").let { sets ->
        (0 until sets.length()).map { i -> sets.getJSONArray(i).strings() }
    }
    private val rows = golden.getJSONArray("rows").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    private fun plan(o: JSONObject) = CommandPlan(
        action = o.getString("action"), source = o.getString("source"), destination = o.getString("destination"),
        kinds = o.getJSONArray("kinds").strings(), extensions = o.getJSONArray("extensions").strings(),
        nameContains = o.getString("name_contains"), contentContains = o.getString("content_contains"),
        olderThan = o.getInt("older_than"), newerThan = o.getInt("newer_than"), ageUnit = o.getString("age_unit"),
        largerThan = o.getInt("larger_than"), sizeUnit = o.getString("size_unit"), sort = o.getString("sort"),
        limit = o.getInt("limit"), groupBy = o.getString("group_by"), today = o.optBoolean("today")
    )

    /** What the app does with one command: the router first, then the fixes, as phase0b.score_all. */
    private fun kotlinResult(row: JSONObject): Any = when (val route = CommandRules.route(row.getString("text"))) {
        is CommandRules.Route.Refuse -> "refuse"
        is CommandRules.Route.Screen -> route.plan
        null -> CommandRules.fix(row.getString("text"), plan(row.getJSONObject("raw")), folders[row.getInt("folders")])
    }

    @Test
    fun everyLabCaseGivesThePythonResult() {
        assertEquals(816, rows.size)
        val wrong = rows.filter { row ->
            val expected: Any = if (row.optBoolean("refuse")) "refuse" else plan(row.getJSONObject("final"))
            kotlinResult(row) != expected
        }
        assertTrue(
            "${wrong.size} of ${rows.size} differ:\n" + wrong.take(20).joinToString("\n") {
                "${it.getString("variant")} ${it.getString("id")} ${it.getString("text")}\n  kotlin ${kotlinResult(it)}\n" +
                    "  python ${it.opt("final") ?: "refuse"}"
            },
            wrong.isEmpty()
        )
    }

    @Test
    fun routerDecidesTheSameCasesWithoutTheModel() {
        rows.forEach { row ->
            assertEquals(row.getString("text"), row.getBoolean("routed"), CommandRules.route(row.getString("text")) != null)
        }
    }

    @Test
    fun promptAndGrammarAreLabPromptK() {
        assertEquals(golden.getString("system_k"), CommandPlanner.SYSTEM_K)
        assertEquals(golden.getString("grammar_k"), CommandPlanner.GRAMMAR_K)
    }

    @Test
    fun refusalSaysWhatIsMissing() {
        fun reason(text: String) = (CommandRules.route(text) as CommandRules.Route.Refuse).reason
        assertEquals(CommandRules.Refusal.OPENED, reason("Zeig mir Dateien, die ich seit einer Woche nicht geöffnet habe"))
        assertEquals(CommandRules.Refusal.SEARCHED, reason("Ich hab gestern eine Datei gesucht, zeig sie nochmal"))
        assertEquals(CommandRules.Refusal.HABIT, reason("Sortier den Download so wie ich es normalerweise mache"))
        assertEquals(CommandRules.Refusal.CONVERSATION, reason("Mach mit den restlichen Bildern das gleiche"))
        assertEquals(CommandRules.Refusal.WORK, reason("Räum meine Arbeitsdokumente auf"))
    }

    // Found after the rules were frozen (docs/KI-ZEIGEN-PHASE0B.md, section 4) and not measured yet. Kept as
    // pending tests so the fix is a deliberate change in rules.py first, never a silent difference in the port.
    @Ignore("rule error found late, not measured: the content-word rule overwrites the model's correct content word")
    @Test
    fun contentWordOfTheModelIsKept() {
        val raw = CommandPlan("show", "", nameContains = "rechnung", contentContains = "mediamarkt")
        val fixed = CommandRules.fix("ich such die rechnung von mediamarkt weiß aber nicht wie die datei heisst", raw)
        assertEquals("mediamarkt", fixed.contentContains.lowercase())
    }

    @Ignore("rule error found late, not measured: a time the model invents stays when the sentence has no time word")
    @Test
    fun timeWithoutTimeWordIsDropped() {
        val raw = CommandPlan("show", "Kamera", kinds = listOf("image"), newerThan = 1, sort = "newest", limit = 1)
        assertEquals(0, CommandRules.fix("zeig mir das neueste foto aus der kamera", raw).newerThan)
    }
}
