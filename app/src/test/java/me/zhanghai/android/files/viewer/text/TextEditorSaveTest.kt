/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEditorSaveTest {
    @Test
    fun typingDuringSaveStaysDirty() {
        assertTrue(savedTextIsCurrent("abc", "abc"))
        assertFalse(savedTextIsCurrent("abcd", "abc"))
        assertFalse(savedTextIsCurrent("ab", "abc"))
    }
}
