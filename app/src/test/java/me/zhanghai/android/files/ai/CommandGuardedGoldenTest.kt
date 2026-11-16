/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin net against rules2.decide (flags ALL, frozen 430da086): every recorded prompt-C plan of the lab (dev sets,
 * blind 2, blind 3) and, for coverage, every other recorded raw plan and the training targets, with the phone as it
 * was. Fixture: tools/memory-lab/export_guarded_golden.py. A difference is a bug in the port, never a reason to
 * change the fixture.
 */
class CommandGuardedGoldenTest {
    private val golden = CommandNetGolden.read(javaClass.getResourceAsStream("/ai/guarded-golden.json.gz")!!)

    @Test
    fun everyLabPlanGivesThePythonDecision() {
        val result = CommandNetGolden.guarded(golden)
        println("guarded golden: $result, largest subset search ${CommandNetGolden.maxSubsetTerms} words")
        assertTrue("the subset cap changes a lab decision", CommandNetGolden.maxSubsetTerms <= CommandNetDecide.MAX_SUBSET_TERMS)
        assertTrue(result.toString(), result.equal == result.total && result.total > 0)
    }

    @Test
    fun promptAndGrammarAreLabPromptC() {
        assertEquals(golden.getString("system"), CommandNetLexicon.SYSTEM_C)
        assertEquals(golden.getString("grammar"), CommandNetLexicon.GRAMMAR_C)
    }
}
