/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class LayoutResourcesTest {
    private val layouts = File("src/main/res").listFiles { file -> file.name.startsWith("layout") }!!
        .flatMap { it.listFiles { file -> file.extension == "xml" }!!.toList() }

    private fun elements(file: File): List<Element> =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            .getElementsByTagName("*").let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    @Test
    fun homeSearchFieldStaysInTheTopBar() {
        val searchCard = elements(File("src/main/res/layout/home_fragment.xml"))
            .single { it.getAttribute("android:id") == "@+id/searchCard" }
        val ancestors = generateSequence(searchCard.parentNode) { it.parentNode }.map { it.nodeName }.toList()
        assertTrue("search field is not in the top bar", "eightbitlab.com.blurview.BlurView" in ancestors)
        assertTrue("search field scrolls away", ancestors.none { "ScrollView" in it })
    }

    @Test
    fun waitingUsesOneLoadingAnimation() {
        assertTrue("layouts not found", layouts.size > 50)
        var loadingIndicators = 0
        for (file in layouts) {
            for (element in elements(file)) {
                val name = element.tagName
                assertTrue("${file.name} uses the platform spinner", name != "ProgressBar")
                if (name.endsWith(".CircularProgressIndicator")) {
                    // A ring is only for progress that can show a percentage.
                    assertTrue(
                        "${file.name} spins without the loading animation",
                        element.hasAttribute("app:indicatorSize")
                    )
                }
                if (name.endsWith(".LoadingIndicator")) {
                    ++loadingIndicators
                }
            }
        }
        assertEquals(12, loadingIndicators)
    }
}
