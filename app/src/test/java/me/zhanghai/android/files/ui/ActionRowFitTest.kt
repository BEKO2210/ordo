/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionRowFitTest {
    private val labels = listOf(ActionLabel(60f, 32f, 26f), ActionLabel(80f, 32f, 26f))

    @Test
    fun roomForEverythingKeepsIconsAndRow() =
        assertEquals(ActionRowMode.SIDE_BY_SIDE, chooseActionRowMode(labels, 140f))

    @Test
    fun noRoomForIconsDropsThemFirst() =
        assertEquals(ActionRowMode.SIDE_BY_SIDE_WITHOUT_ICONS, chooseActionRowMode(labels, 120f))

    @Test
    fun noRoomForTheLongestWordStacks() =
        assertEquals(ActionRowMode.STACKED, chooseActionRowMode(labels, 100f))

    @Test
    fun exactFitCounts() =
        assertEquals(ActionRowMode.SIDE_BY_SIDE, chooseActionRowMode(labels, 138f))
}
