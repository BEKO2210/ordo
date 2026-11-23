/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The file command keeps running when the sheet is recreated (rotation). The result waits here
 * until a live sheet can show the snackbar; the journal itself never goes through the fragment.
 * Touched from the main thread only.
 */
internal object CommandUndo {
    class Offer(
        val message: String,
        val canUndo: Boolean,
        val undo: suspend () -> Int,
        val undoneText: (Int) -> String,
        val scan: () -> Unit,
    )

    private val offersFlow = MutableStateFlow<Offer?>(null)
    val offers: StateFlow<Offer?> = offersFlow.asStateFlow()

    /** True while [CommandSheetFragment.execute] is still moving files. */
    @Volatile
    var running: Boolean = false

    fun offer(offer: Offer) {
        offersFlow.value = offer
    }

    /** True the first time this exact offer is taken. */
    fun consume(offer: Offer): Boolean {
        if (offersFlow.value !== offer) return false
        offersFlow.value = null
        return true
    }

    fun clear() {
        offersFlow.value = null
        running = false
    }

    enum class Restore {
        /** The command finished while no sheet could show it. */
        ShowPendingUndo,
        /** The command is still running; do not start it again. */
        WaitForRunningCommand,
        /** Opened with a fixed plan; show that plan, do not ask the model. */
        EvaluatePreset,
        /** The user was waiting on a model answer. */
        ResumePendingText,
        Fresh,
    }

    fun restore(
        undoPending: Boolean,
        commandRunning: Boolean,
        presetPlan: Boolean,
        restoredPending: Boolean,
    ): Restore = when {
        undoPending -> Restore.ShowPendingUndo
        commandRunning -> Restore.WaitForRunningCommand
        presetPlan -> Restore.EvaluatePreset
        restoredPending -> Restore.ResumePendingText
        else -> Restore.Fresh
    }
}
