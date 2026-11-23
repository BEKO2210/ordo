/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        CommandUndo.publish(offer, now = 0, windowMs = 8_000)
        assertSame(offer, CommandUndo.pending(0))
        assertTrue(CommandUndo.drop(offer))
        assertFalse(CommandUndo.drop(offer))
        assertNull(CommandUndo.pending(0))
    }

    @Test
    fun presetPlanWinsOverAPendingModelSubmit() {
        assertEquals(
            CommandUndo.Restore.EvaluatePreset,
            CommandUndo.restore(now = 0, hostId = 1, presetPlan = true, restoredPending = true)
        )
    }

    @Test
    fun runningCommandIsNotStartedAgain() {
        CommandUndo.running = true
        assertEquals(
            CommandUndo.Restore.WaitForRunningCommand,
            CommandUndo.restore(now = 0, hostId = 1, presetPlan = true, restoredPending = true)
        )
    }

    @Test
    fun finishedCommandShowsUndoBeforeAnythingElse() {
        CommandUndo.publish(offer(), now = 0, windowMs = 8_000)
        CommandUndo.running = true
        assertEquals(
            CommandUndo.Restore.ShowPendingUndo,
            CommandUndo.restore(now = 0, hostId = 1, presetPlan = true, restoredPending = true)
        )
    }

    @Test
    fun plainPendingTextStillResumesAndAFreshSheetStaysFresh() {
        assertEquals(
            CommandUndo.Restore.ResumePendingText,
            CommandUndo.restore(now = 0, hostId = 1, presetPlan = false, restoredPending = true)
        )
        assertEquals(
            CommandUndo.Restore.Fresh,
            CommandUndo.restore(now = 0, hostId = 1, presetPlan = false, restoredPending = false)
        )
    }

    @Test
    fun jobEndingDuringRotationStaysForTheNextSheet() {
        CommandUndo.running = true
        val offer = offer()
        CommandUndo.publish(offer, now = 0, windowMs = 8_000)
        assertNull(CommandUndo.claimForDisplay(UndoHost.Departing, hostId = 1, now = 0))
        assertNull(CommandUndo.shownBy(0))
        CommandUndo.running = false
        assertEquals(
            CommandUndo.Restore.ShowPendingUndo,
            CommandUndo.restore(now = 10, hostId = 2, presetPlan = false, restoredPending = true)
        )
        assertSame(offer, CommandUndo.claimForDisplay(UndoHost.Current, hostId = 2, now = 10))
    }

    @Test
    fun snackbarRotatedAwayCanBeShownAgain() {
        val offer = offer()
        CommandUndo.publish(offer, now = 0, windowMs = 8_000)
        assertNotNull(CommandUndo.claimForDisplay(UndoHost.Current, hostId = 1, now = 0))
        assertNull(CommandUndo.claimForDisplay(UndoHost.Current, hostId = 1, now = 1_000))
        assertEquals(
            CommandUndo.Restore.UndoOnThisScreen,
            CommandUndo.restore(now = 1_000, hostId = 1, presetPlan = false, restoredPending = true)
        )
        CommandUndo.releaseDisplay(offer, hostId = 1)
        assertEquals(
            CommandUndo.Restore.ShowPendingUndo,
            CommandUndo.restore(now = 1_000, hostId = 2, presetPlan = false, restoredPending = true)
        )
        assertSame(offer, CommandUndo.claimForDisplay(UndoHost.Current, hostId = 2, now = 1_000))
    }

    @Test
    fun releaseFromTheOldHostDoesNotClearTheNewHostsSnackbar() {
        val offer = offer()
        CommandUndo.publish(offer, now = 0, windowMs = 8_000)
        CommandUndo.claimForDisplay(UndoHost.Current, hostId = 1, now = 0)
        CommandUndo.claimForDisplay(UndoHost.Current, hostId = 2, now = 100)
        CommandUndo.releaseDisplay(offer, hostId = 1)
        assertEquals(2, CommandUndo.shownBy(100))
    }

    @Test
    fun runningFalseWithoutAnOfferLeavesLoading() {
        CommandUndo.running = true
        assertEquals(UndoUi.KeepWaiting, undoUi(UndoHost.Current, false, false, commandRunning = true, holding = true))
        CommandUndo.running = false
        assertEquals(
            CommandUndo.Restore.Fresh,
            CommandUndo.restore(now = 0, hostId = 1, presetPlan = false, restoredPending = false)
        )
        assertEquals(UndoUi.LeaveLoading, undoUi(UndoHost.Current, false, false, commandRunning = false, holding = true))
        assertEquals(UndoUi.Untouched, undoUi(UndoHost.Current, false, false, commandRunning = false, holding = false))
    }

    @Test
    fun departingHostDoesNotBindTheOffer() {
        assertEquals(UndoHost.Departing, undoHost(
            activityPresent = true, finishing = false, destroyed = false,
            changingConfigurations = true, viewReady = true, atLeastStarted = true
        ))
        assertEquals(UndoHost.Departing, undoHost(
            activityPresent = true, finishing = false, destroyed = false,
            changingConfigurations = false, viewReady = true, atLeastStarted = false
        ))
        assertEquals(UndoHost.Absent, undoHost(
            activityPresent = true, finishing = false, destroyed = true,
            changingConfigurations = true, viewReady = true, atLeastStarted = true
        ))
        assertEquals(UndoHost.Current, undoHost(
            activityPresent = true, finishing = false, destroyed = false,
            changingConfigurations = false, viewReady = true, atLeastStarted = true
        ))
        assertEquals(
            UndoUi.KeepWaiting,
            undoUi(UndoHost.Departing, offerWaiting = true, shownHere = false, commandRunning = false, holding = false)
        )
        assertEquals(
            UndoUi.ShowUndo,
            undoUi(UndoHost.Current, offerWaiting = true, shownHere = false, commandRunning = true, holding = true)
        )
    }

    @Test
    fun undoWindowEndsAndALaterTimerDoesNotDropTheNextOffer() {
        val first = offer()
        CommandUndo.publish(first, now = 0, windowMs = 8_000)
        assertFalse(CommandUndo.expire(7_999))
        assertTrue(CommandUndo.expire(8_000))
        assertEquals(
            CommandUndo.Restore.Fresh,
            CommandUndo.restore(now = 8_000, hostId = 1, presetPlan = false, restoredPending = false)
        )
        val second = offer()
        CommandUndo.publish(second, now = 1_000, windowMs = 8_000)
        assertFalse(CommandUndo.drop(first))
        assertSame(second, CommandUndo.pending(1_000))
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
