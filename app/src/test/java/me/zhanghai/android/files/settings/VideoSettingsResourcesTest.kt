/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class VideoSettingsResourcesTest {
    private fun parse(file: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$file")).documentElement

    // House rule "never text only": every row of Ordo Video's settings carries a symbol, as the other settings do.
    @Test
    fun everyVideoSettingHasASymbol() {
        val all = parse("xml/settings_video.xml").getElementsByTagName("*")
        val rows = (0 until all.length).map { all.item(it) as Element }
            .filter { it.tagName != "PreferenceCategory" && it.tagName != "PreferenceScreen" }
        assertTrue(rows.size > 20)
        for (row in rows) {
            val icon = row.getAttribute("android:icon")
            assertTrue("${row.getAttribute("android:key")} has no symbol", icon.startsWith("@drawable/"))
            assertTrue(icon, File("src/main/res/drawable/${icon.removePrefix("@drawable/")}.xml").exists())
        }
    }

    // The upscaling sheet explains every choice: one summary per entry, in English and German.
    @Test
    fun everyUpscalerChoiceHasAnExplanation() {
        for (values in listOf("values", "values-de")) {
            val arrays = parse("$values/video_picture_strings.xml").getElementsByTagName("string-array")
            fun items(name: String) = (0 until arrays.length).map { arrays.item(it) as Element }
                .single { it.getAttribute("name") == name }.getElementsByTagName("item").length
            assertEquals(values, items("video_settings_upscaler_entries"), items("video_settings_upscaler_summaries"))
        }
    }
}
