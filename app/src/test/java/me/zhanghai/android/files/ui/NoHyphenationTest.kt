/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * House rule: no word breaks in the middle. The app themes switch hyphenation off and use the simple line breaker for
 * every TextView, including those built in code inside the player sheets (a BottomSheetDialog's theme overlays the
 * activity's); nothing may opt back in.
 */
class NoHyphenationTest {
    private val main = File("src/main")

    @Test
    fun everyAppThemeRootTurnsHyphenationOff() {
        val themes = File(main, "res/values").listFiles { file -> file.name.startsWith("themes") }!!
            .joinToString("\n") { it.readText() }
        for (root in listOf(
            "Platform.V23.Theme.MaterialFiles", "Platform.V23.Theme.MaterialFiles.Immersive",
            "Platform.V23.Theme.MaterialFiles.Material3", "Platform.V23.Theme.MaterialFiles.Material3.Immersive"
        )) {
            val style = Regex("""<style name="${Regex.escape(root)}"[^>]*>(.*?)</style>""", RegexOption.DOT_MATCHES_ALL)
                .find(themes)?.groupValues?.get(1)
            assertTrue("$root missing", style != null)
            assertTrue("$root hyphenates", style!!.contains("""<item name="android:hyphenationFrequency">none</item>"""))
            assertTrue("$root breaks balanced", style.contains("""<item name="android:breakStrategy">simple</item>"""))
        }
    }

    @Test
    fun nothingOptsBackIntoHyphenation() {
        val optIn = Regex(
            """HYPHENATION_FREQUENCY_(NORMAL|FULL)|BREAK_STRATEGY_(BALANCED|HIGH_QUALITY)|""" +
                """hyphenationFrequency(">|=")(normal|full)|breakStrategy(">|=")(balanced|high_quality)"""
        )
        val hits = main.walk().filter { it.isFile && it.extension in setOf("kt", "java", "xml") }
            .flatMap { file -> optIn.findAll(file.readText()).map { "${file.name}: ${it.value}" } }
            .toList()
        assertEquals(emptyList<String>(), hits)
    }
}
