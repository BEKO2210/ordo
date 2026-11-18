/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WordBreaksTest {
    @Test
    fun breakInsideAWordIsFound() {
        // "Absp|ielen" and "Zufäll|ig", as on the owner's tablet screenshot.
        assertEquals(listOf(4), WordBreaks.midWord("Abspielen", intArrayOf(4)))
        assertEquals(listOf(6), WordBreaks.midWord("Zufällig", intArrayOf(6)))
    }

    @Test
    fun breaksAtSpacesHyphensAndTheEndAreFine() {
        assertEquals(emptyList<Int>(), WordBreaks.midWord("Alle zufällig", intArrayOf(5)))
        assertEquals(emptyList<Int>(), WordBreaks.midWord("E-Mail", intArrayOf(2)))
        assertEquals(emptyList<Int>(), WordBreaks.midWord("Titel", intArrayOf(5)))
    }

    @Test
    fun chineseAndJapaneseBreakBetweenLetters() {
        assertEquals(emptyList<Int>(), WordBreaks.midWord("播放全部", intArrayOf(2)))
    }
}
