/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The file command keeps running when the sheet is recreated (rotation). The result is published
 * here and only a started, current host shows it. A snackbar that dies with the old window puts
 * the offer back, so the next host can show it until the window ends. Touched from the main thread.
 */
internal object CommandUndo {
    class Offer(
        val message: String,
        val canUndo: Boolean,
        val undo: suspend () -> Int,
        val undoneText: (Int) -> String,
        val scan: () -> Unit,
    )

    private class Slot(val offer: Offer, val expiresAt: Long, var shownBy: Int?)

    private var slot: Slot? = null

    private val revisionFlow = MutableStateFlow(0)

    /** Bumps when an offer is published, released, dropped or the command stops or starts. */
    val revision: StateFlow<Int> = revisionFlow.asStateFlow()

    private val runningFlow = MutableStateFlow(false)

    /** True while [CommandSheetFragment.execute] is still moving files. */
    var running: Boolean
        get() = runningFlow.value
        set(value) {
            if (runningFlow.value == value) return
            runningFlow.value = value
            bump()
        }

    /** [windowMs] starts at [now]. A later [publish] replaces this offer. */
    fun publish(offer: Offer, now: Long, windowMs: Long) {
        slot = Slot(offer, expiresAt = now + windowMs, shownBy = null)
        bump()
    }

    fun pending(now: Long): Offer? = live(now)?.offer

    /** Host id that is currently showing this offer, or null when nobody is. */
    fun shownBy(now: Long): Int? = live(now)?.shownBy

    fun remainingMs(now: Long): Long {
        val current = slot ?: return 0
        return (current.expiresAt - now).coerceAtLeast(0)
    }

    /** The current host may show this. The same host does not take it twice. */
    fun claimForDisplay(host: UndoHost, hostId: Int, now: Long): Offer? {
        if (host != UndoHost.Current) return null
        val current = live(now) ?: return null
        if (current.shownBy == hostId) return null
        current.shownBy = hostId
        return current.offer
    }

    /** The snackbar left with the window (rotation). The offer stays until it expires or is dropped. */
    fun releaseDisplay(offer: Offer, hostId: Int) {
        val current = slot ?: return
        if (current.offer !== offer || current.shownBy != hostId) return
        current.shownBy = null
        bump()
    }

    /** True the first time this exact offer is removed (timeout, swipe or undo). */
    fun drop(offer: Offer): Boolean {
        val current = slot ?: return false
        if (current.offer !== offer) return false
        slot = null
        bump()
        return true
    }

    /** Drops the offer once [now] is at the end of its window. */
    fun expire(now: Long): Boolean {
        val current = slot ?: return false
        if (now < current.expiresAt) return false
        return drop(current.offer)
    }

    fun clear() {
        slot = null
        runningFlow.value = false
        revisionFlow.value = 0
    }

    fun restore(now: Long, hostId: Int, presetPlan: Boolean, restoredPending: Boolean): Restore {
        val current = live(now)
        return when {
            current != null && current.shownBy != hostId -> Restore.ShowPendingUndo
            current != null -> Restore.UndoOnThisScreen
            running -> Restore.WaitForRunningCommand
            presetPlan -> Restore.EvaluatePreset
            restoredPending -> Restore.ResumePendingText
            else -> Restore.Fresh
        }
    }

    private fun live(now: Long): Slot? {
        val current = slot ?: return null
        if (now >= current.expiresAt) {
            slot = null
            bump()
            return null
        }
        return current
    }

    private fun bump() {
        revisionFlow.value = revisionFlow.value + 1
    }

    enum class Restore {
        /** The command finished and this host is not the one showing the snackbar. */
        ShowPendingUndo,
        /** The command is still running; do not start it again. */
        WaitForRunningCommand,
        /** This activity already shows the undo snackbar. Do not run the command again. */
        UndoOnThisScreen,
        /** Opened with a fixed plan; show that plan, do not ask the model. */
        EvaluatePreset,
        /** The user was waiting on a model answer. */
        ResumePendingText,
        Fresh,
    }
}

internal enum class UndoHost {
    /** Started, and not finishing or changing configuration. */
    Current,
    /** Still around, but going away or not started yet. */
    Departing,
    Absent,
}

internal fun undoHost(
    activityPresent: Boolean,
    finishing: Boolean,
    destroyed: Boolean,
    changingConfigurations: Boolean,
    viewReady: Boolean,
    atLeastStarted: Boolean,
): UndoHost = when {
    !activityPresent || destroyed || !viewReady -> UndoHost.Absent
    finishing || changingConfigurations || !atLeastStarted -> UndoHost.Departing
    else -> UndoHost.Current
}

internal enum class UndoUi {
    /** Publish is already done; this host shows the snackbar and closes. */
    ShowUndo,
    /** Stay on the running screen. Do not bind the offer to a departing window. */
    KeepWaiting,
    /** The command ended with nothing left to show. Leave the loading screen. */
    LeaveLoading,
    /** Nothing about undo changed for this host. */
    Untouched,
}

internal fun undoUi(
    host: UndoHost,
    offerWaiting: Boolean,
    shownHere: Boolean,
    commandRunning: Boolean,
    holding: Boolean,
): UndoUi = when {
    shownHere -> UndoUi.Untouched
    offerWaiting && host == UndoHost.Current -> UndoUi.ShowUndo
    offerWaiting || commandRunning -> UndoUi.KeepWaiting
    holding -> UndoUi.LeaveLoading
    else -> UndoUi.Untouched
}
