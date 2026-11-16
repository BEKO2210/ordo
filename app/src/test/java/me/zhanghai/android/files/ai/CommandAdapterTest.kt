/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import me.zhanghai.android.files.ai.CommandModel.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandAdapterTest {
    private val sha = "a91389c7e51f3c57938dbead18c59388ae785b08cd051319e57d6a0a232d7bc0"
    private val adapter = CommandModel.Part("ordo-run1.gguf", 42_485_408L, sha, "")

    @Test
    fun withoutAdapterOnlyTheModel() {
        assertEquals(listOf(CommandModel.MODEL), CommandModel.parts(CommandDesign.Today))
        assertNull(CommandDesign.Today.adapter)
        assertNull(CommandDesign.KR.adapter)
    }

    @Test
    fun adapterKeepsTheBaseDesign() {
        val design = CommandDesign.WithAdapter(CommandDesign.Today, adapter)
        assertSame(CommandDesign.Today.system, design.system)
        assertSame(CommandDesign.Today.grammar, design.grammar)
        assertEquals(listOf(CommandModel.MODEL, adapter), CommandModel.parts(design))
    }

    @Test
    fun parsesTheDebugSpec() {
        assertEquals(adapter, CommandDesign.parseAdapter("ordo-run1.gguf,42485408,$sha"))
        assertEquals(adapter.copy(url = "https://x/y"), CommandDesign.parseAdapter("ordo-run1.gguf,42485408,$sha,https://x/y"))
        assertNull(CommandDesign.parseAdapter(""))
        assertNull(CommandDesign.parseAdapter("a.gguf,12,nothex"))
        assertNull(CommandDesign.parseAdapter("../a.gguf,12,$sha"))
        assertNull(CommandDesign.parseAdapter("a.gguf,0,$sha"))
        assertNull(CommandDesign.parseAdapter("a.gguf,x,$sha"))
    }

    @Test
    fun oneModelPartIsReportedAsBefore() {
        val parts = listOf(CommandModel.MODEL)
        for (state in listOf(State.Missing, State.Verifying, State.Ready, State.Failed(true),
                State.Downloading(5, CommandModel.SIZE, true, false))) {
            assertEquals(state, CommandModel.combine(listOf(state), parts))
        }
    }

    @Test
    fun partsTogether() {
        val parts = listOf(CommandModel.MODEL, adapter)
        val total = CommandModel.SIZE + adapter.size
        assertEquals(State.Ready, CommandModel.combine(listOf(State.Ready, State.Ready), parts))
        assertEquals(State.Missing, CommandModel.combine(listOf(State.Ready, State.Missing), parts))
        assertEquals(State.Verifying, CommandModel.combine(listOf(State.Ready, State.Verifying), parts))
        assertEquals(State.Failed(false), CommandModel.combine(
            listOf(State.Downloading(1, CommandModel.SIZE, false, false), State.Failed(false)), parts))
        // The finished model counts in full while the adapter still downloads.
        assertEquals(State.Downloading(CommandModel.SIZE + 7, total, false, true), CommandModel.combine(
            listOf(State.Verifying, State.Downloading(7, adapter.size, false, true)), parts))
    }

    @Test
    fun retryRestartsOnlyWhatFailed() {
        val parts = listOf(CommandModel.MODEL, adapter)
        val downloading = State.Downloading(1_000_000_000L, CommandModel.SIZE, false, false)
        for (mobile in listOf(false, true)) {
            // The adapter failed while the model still downloads: the retry restarts the adapter, the model keeps its bytes.
            assertEquals(listOf(adapter), CommandModel.toStart(listOf(downloading, State.Failed(false)), parts, mobile))
            assertEquals(listOf(adapter), CommandModel.toStart(listOf(State.Verifying, State.Missing), parts, mobile))
            assertEquals(listOf(adapter), CommandModel.toStart(listOf(State.Ready, State.Failed(true)), parts, mobile))
            assertEquals(parts, CommandModel.toStart(listOf(State.Missing, State.Missing), parts, mobile))
        }
    }

    @Test
    fun theModelAloneAsOnMain() {
        val model = listOf(CommandModel.MODEL)
        val wifi = State.Downloading(5, CommandModel.SIZE, false, true)
        val running = State.Downloading(5, CommandModel.SIZE, false, false)
        // "Use mobile data" while queued for Wi-Fi requeues it with mobile data allowed.
        assertEquals(model, CommandModel.toStart(listOf(wifi), model, mobileData = true))
        // Without mobile data, or while it actually downloads, nothing is restarted.
        assertEquals(emptyList<CommandModel.Part>(), CommandModel.toStart(listOf(wifi), model, mobileData = false))
        assertEquals(emptyList<CommandModel.Part>(), CommandModel.toStart(listOf(running), model, mobileData = true))
        assertEquals(emptyList<CommandModel.Part>(), CommandModel.toStart(listOf(State.Verifying), model, mobileData = true))
        assertEquals(emptyList<CommandModel.Part>(), CommandModel.toStart(listOf(State.Ready), model, mobileData = true))
        // Missing or failed: the setup and retry buttons start it, with or without mobile data.
        for (mobile in listOf(false, true)) {
            assertEquals(model, CommandModel.toStart(listOf(State.Missing), model, mobile))
            assertEquals(model, CommandModel.toStart(listOf(State.Failed(false)), model, mobile))
            assertEquals(model, CommandModel.toStart(listOf(State.Failed(true)), model, mobile))
        }
    }

    @Test
    fun spaceCountsTheAdapter() {
        val need = CommandModel.SIZE + adapter.size
        val check = CommandModel.DeviceCheck(12_000_000_000L, need + CommandModel.SPARE_SPACE - 1, true, true, need)
        assertFalse(check.spaceOk)
        assertTrue(check.copy(freeBytes = need + CommandModel.SPARE_SPACE).spaceOk)
    }
}
