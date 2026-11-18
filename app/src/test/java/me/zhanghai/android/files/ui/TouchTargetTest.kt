/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * House rule: everything that can be tapped is at least 48 dp in both directions. Every button, switch, check box,
 * radio button, chip and clickable view in a layout that declares a smaller size needs a minWidth/minHeight of 48 dp
 * (0 dp under a layout_weight is fine: the weight decides).
 */
class TouchTargetTest {
    private val res = File("src/main/res")
    private val parser = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }

    private val dimens: Map<String, String> = res.listFiles { file -> file.name.startsWith("values") }!!
        .flatMap { dir -> dir.listFiles { file -> file.name.startsWith("dimens") }?.toList() ?: emptyList() }
        .flatMap { file ->
            val nodes = parser.newDocumentBuilder().parse(file).getElementsByTagName("dimen")
            (0 until nodes.length).map { nodes.item(it) as Element }
        }
        .associate { it.getAttribute("name") to it.textContent.trim() }

    private fun dp(value: String): Float? {
        var v = value
        repeat(4) { if (v.startsWith("@dimen/")) v = dimens[v.removePrefix("@dimen/")] ?: "" }
        return Regex("""([\d.]+)dp""").matchEntire(v)?.groupValues?.get(1)?.toFloat()
    }

    @Test
    fun everyTappableViewInALayoutIsAtLeast48dp() {
        val interactive = listOf("Button", "ImageButton", "CheckBox", "Switch", "RadioButton", "Chip")
        val tooSmall = mutableListOf<String>()
        res.listFiles { file -> file.name.startsWith("layout") }!!.flatMap { it.listFiles()!!.toList() }
            .filter { it.extension == "xml" }
            .forEach { file ->
                val all = parser.newDocumentBuilder().parse(file).getElementsByTagName("*")
                for (i in 0 until all.length) {
                    val view = all.item(i) as Element
                    val tappable = view.getAttribute("android:clickable") == "true" ||
                        interactive.any { view.tagName.endsWith(it) }
                    if (!tappable) continue
                    for ((size, min) in listOf("layout_width" to "minWidth", "layout_height" to "minHeight")) {
                        val declared = dp(view.getAttribute("android:$size")) ?: continue
                        if (declared == 0f || declared >= 48f) continue
                        if ((dp(view.getAttribute("android:$min")) ?: 0f) >= 48f) continue
                        tooSmall += "${file.parentFile!!.name}/${file.name} ${view.getAttribute("android:id")} " +
                            "$size=${declared}dp"
                    }
                }
            }
        assertEquals(emptyList<String>(), tooSmall)
    }
}
