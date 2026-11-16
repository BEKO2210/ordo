/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin net against rules3.decide (flags "abcd", frozen 13bfbed9): every raw schema-L plan the lab recorded
 * (the frozen adapter, the other runs, the base model) on every dev set, and the training and validation targets.
 * Fixture: tools/memory-lab/export_tuned_golden.py. A difference is a bug in the port, never a reason to change the
 * fixture.
 */
class CommandTunedGoldenTest {
    private val golden = CommandNetGolden.read(javaClass.getResourceAsStream("/ai/tuned-golden.json.gz")!!)

    @Test
    fun everyLabPlanGivesThePythonDecision() {
        val result = CommandNetGolden.tuned(golden)
        println("tuned golden: $result")
        assertTrue(result.toString(), result.equal == result.total && result.total > 0)
    }

    @Test
    fun promptAndGrammarAreLabSchemaL() {
        assertEquals(golden.getString("system"), CommandNetLexicon.SYSTEM_LORA)
        assertEquals(golden.getString("grammar"), CommandNetLexicon.GRAMMAR_L)
    }
}
