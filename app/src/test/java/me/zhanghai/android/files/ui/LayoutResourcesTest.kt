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

    // One row: side menu, search, assistant, more. Every icon has a TalkBack label and a 48 dp touch target.
    @Test
    fun homeTopBarIconsAreLabelledAndLargeEnough() {
        val buttons = elements(File("src/main/res/layout/home_fragment.xml")).filter { it.tagName == "ImageButton" }
        assertEquals(
            listOf("@+id/menuButton", "@+id/assistantButton", "@+id/moreButton"),
            buttons.map { it.getAttribute("android:id") }
        )
        for (button in buttons) {
            val id = button.getAttribute("android:id")
            assertTrue("$id has no label", button.getAttribute("android:contentDescription").startsWith("@string/"))
            assertEquals(id, "48dp", button.getAttribute("android:layout_width"))
            assertEquals(id, "48dp", button.getAttribute("android:layout_height"))
        }
    }

    @Test
    fun homeHasTheSideMenuOfTheFileList() {
        val root = elements(File("src/main/res/layout/home_fragment.xml")).first()
        assertEquals("androidx.drawerlayout.widget.DrawerLayout", root.tagName)
        val fileList = elements(File("src/main/res/layout/file_list_fragment_include.xml"))
        for (layout in listOf(elements(File("src/main/res/layout/home_fragment.xml")), fileList)) {
            val drawer = layout.single { it.getAttribute("android:id") == "@+id/navigationFragment" }
            assertEquals("start", drawer.getAttribute("android:layout_gravity"))
        }
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
        // The storage scan is not among them: it has its own picture, ScanProgressView, which ends as a chart.
        assertEquals(10, loadingIndicators)
    }
}
