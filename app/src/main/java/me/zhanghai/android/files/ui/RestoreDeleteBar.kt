/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ui

import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.files.R

/**
 * A bottom bar reaches the screen edge; only its content stays clear of the system bars and the
 * keyboard.
 */
fun View.fitBottomBarInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val insets = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime()
        )
        view.updatePadding(left = insets.left, right = insets.right, bottom = insets.bottom)
        windowInsets
    }
}

/** Wires the bar from `restore_delete_bar_include.xml`. */
fun View.setUpRestoreDeleteBar(onRestore: () -> Unit, onDelete: () -> Unit) {
    fitBottomBarInsets()
    findViewById<View>(R.id.restoreButton).setOnClickListener { onRestore() }
    findViewById<View>(R.id.deletePermanentlyButton).setOnClickListener { onDelete() }
}

fun View.showRestoreDeleteBar(show: Boolean, list: RecyclerView) {
    if (show && !isVisible) {
        // Slides up from the bottom edge instead of popping in.
        translationY = resources.displayMetrics.density * 72
        animate().translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator())
            .start()
    }
    isVisible = show
    // Keeps the last rows reachable above the bar.
    val listPadding = resources.getDimensionPixelSize(R.dimen.list_vertical_padding)
    if (show) {
        doOnLayout { list.updatePadding(bottom = listPadding + height) }
    } else {
        list.updatePadding(bottom = listPadding)
    }
}
