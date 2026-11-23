/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandUndoTest {
    @After
    fun tearDown() {
        CommandUndo.clear()
    }

    @Test
    fun offerIsTakenOnce() {
        val offer = offer()
        CommandUndo.offer(offer)
        assertSame(offer, CommandUndo.offers.value)
        assertTrue(CommandUndo.consume(offer))
        assertFalse(CommandUndo.consume(offer))
        assertEquals(null, CommandUndo.offers.value)
    }

    @Test
    fun presetPlanWinsOverAPendingModelSubmit() {
        assertEquals(
            CommandUndo.Restore.EvaluatePreset,
            CommandUndo.restore(
                undoPending = false, commandRunning = false, presetPlan = true, restoredPending = true
            )
        )
    }

    @Test
    fun runningCommandIsNotStartedAgain() {
        assertEquals(
            CommandUndo.Restore.WaitForRunningCommand,
            CommandUndo.restore(
                undoPending = false, commandRunning = true, presetPlan = true, restoredPending = true
            )
        )
    }

    @Test
    fun finishedCommandShowsUndoBeforeAnythingElse() {
        assertEquals(
            CommandUndo.Restore.ShowPendingUndo,
            CommandUndo.restore(
                undoPending = true, commandRunning = true, presetPlan = true, restoredPending = true
            )
        )
    }

    @Test
    fun plainPendingTextStillResumesAndAFreshSheetStaysFresh() {
        assertEquals(
            CommandUndo.Restore.ResumePendingText,
            CommandUndo.restore(
                undoPending = false, commandRunning = false, presetPlan = false, restoredPending = true
            )
        )
        assertEquals(
            CommandUndo.Restore.Fresh,
            CommandUndo.restore(
                undoPending = false, commandRunning = false, presetPlan = false, restoredPending = false
            )
        )
    }

    @Test
    fun analysisPlanRoundTripsIntoTheSavedState() {
        val plan = CommandPlan(
            "delete", "/storage/emulated/0/Download", extensions = listOf("apk"), olderThan = 30
        )
        assertEquals(plan, CommandPlan.parse(plan.toJson()))
    }

    private fun offer() = CommandUndo.Offer(
        message = "1 gelöscht",
        canUndo = true,
        undo = { 1 },
        undoneText = { "$it zurück" },
        scan = {},
    )
}
