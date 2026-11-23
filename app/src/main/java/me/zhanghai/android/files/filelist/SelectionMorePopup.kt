/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.filelist

/**
 * Placement of the selection "More" menu: above the bar, on the More button's side, never over the bar.
 *
 * [showX] is the x passed to [androidx.appcompat.view.menu.MenuPopupHelper.show]: with Gravity.END
 * that is the top-end corner on the bar; the helper then subtracts the bar width in LTR.
 */
internal object SelectionMorePopup {
    fun showX(barLeft: Int, buttonLeft: Int, buttonRight: Int, rtl: Boolean): Int =
        if (rtl) buttonLeft - barLeft else buttonRight - barLeft

    fun maxHeight(windowTop: Int, barTop: Int): Int = (barTop - windowTop).coerceAtLeast(0)

    fun sitsAboveBar(popupBottom: Int, barTop: Int): Boolean = popupBottom <= barTop
}
