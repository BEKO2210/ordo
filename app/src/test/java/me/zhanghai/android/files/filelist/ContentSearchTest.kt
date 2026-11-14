/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentSearchTest {
    private fun stream(text: String) = text.toByteArray().inputStream()

    @Test
    fun plainQueryIsLiteralAndCaseInsensitive() {
        val search = ContentSearch("a.b", false, true)
        assertTrue(search.nameMatches("XA.Bx"))
        assertFalse(search.nameMatches("axb"))
    }

    @Test
    fun regexQuery() {
        val search = ContentSearch("^rech\\d+\\.pdf$", true, false)
        assertTrue(search.nameMatches("Rech2024.pdf"))
        assertFalse(search.nameMatches("Rech2024.pdf.bak"))
    }

    @Test
    fun invalidRegexFallsBackToLiteral() {
        val search = ContentSearch("a(b", true, false)
        assertTrue(search.nameMatches("xa(by"))
    }

    @Test
    fun contentMatchesTextNotBinary() {
        val search = ContentSearch("Rechnung", false, true)
        assertTrue(search.contentMatches(stream("Zeile 1\nDie RECHNUNG folgt\n")))
        assertFalse(search.contentMatches(stream("nichts hier\n")))
        val binary = ByteArray(100) { if (it == 10) 0 else 'R'.code.toByte() } +
            "Rechnung".toByteArray()
        assertFalse(search.contentMatches(binary.inputStream()))
    }
}
