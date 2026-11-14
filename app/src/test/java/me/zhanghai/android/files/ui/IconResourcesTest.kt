/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class IconResourcesTest {
    private val resources = File("src/main/res")

    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

    private fun Element.number(name: String, default: Double): Double =
        getAttribute("android:$name").ifEmpty { null }?.toDouble() ?: default

    private fun Element.paths(): List<Element> =
        getElementsByTagName("path").let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    // End points of the path segments. Enough for a bounding box as long as the curves are convex
    // corners, which holds for the launcher glyph.
    private fun endPoints(pathData: String): List<Pair<Double, Double>> {
        val tokens = Regex("[A-Za-z]|-?\\d*\\.?\\d+").findAll(pathData).map { it.value }.toList()
        val points = mutableListOf<Pair<Double, Double>>()
        var x = 0.0
        var y = 0.0
        var index = 0
        fun next(): Double = tokens[index++].toDouble()
        while (index < tokens.size) {
            val command = tokens[index++].single()
            val base = if (command.isLowerCase()) x to y else 0.0 to 0.0
            when (command.uppercaseChar()) {
                'M', 'L' -> {
                    x = base.first + next()
                    y = base.second + next()
                }
                'H' -> x = base.first + next()
                'V' -> y = base.second + next()
                'A' -> {
                    repeat(5) { next() }
                    x = base.first + next()
                    y = base.second + next()
                }
                'Z' -> continue
                else -> error("Unsupported path command $command")
            }
            points += x to y
        }
        return points
    }

    private fun transformedPoints(vector: Element): List<Pair<Double, Double>> =
        vector.paths().flatMap { path ->
            val group = path.parentNode as Element
            val scaleX = group.number("scaleX", 1.0)
            val scaleY = group.number("scaleY", 1.0)
            val pivotX = group.number("pivotX", 0.0)
            val pivotY = group.number("pivotY", 0.0)
            endPoints(path.getAttribute("android:pathData")).map { (x, y) ->
                (x - pivotX) * scaleX + pivotX + group.number("translateX", 0.0) to
                    (y - pivotY) * scaleY + pivotY + group.number("translateY", 0.0)
            }
        }

    // Launchers cut adaptive icons to a shape; only a circle of 66 dp around the centre of the
    // 108 dp layer is always visible. The glyph keeps 4 dp distance to it.
    @Test
    fun launcherGlyphIsCentredInsideTheSafeZone() {
        for (name in listOf("launcher_icon_foreground_vector", "launcher_icon_monochrome")) {
            val vector = parse(File(resources, "drawable/$name.xml"))
            assertEquals(108.0, vector.number("viewportWidth", 0.0), 0.0)
            assertEquals(108.0, vector.number("viewportHeight", 0.0), 0.0)
            val points = transformedPoints(vector)
            val left = points.minOf { it.first }
            val right = points.maxOf { it.first }
            val top = points.minOf { it.second }
            val bottom = points.maxOf { it.second }
            assertEquals("$name is not centred horizontally", 54.0, (left + right) / 2, 0.25)
            assertEquals("$name is not centred vertically", 54.0, (top + bottom) / 2, 0.25)
            val farthest = hypot((right - left) / 2, (bottom - top) / 2)
            assertTrue("$name reaches $farthest dp from the centre", farthest <= 33 - 4)
            assertTrue("$name is only ${right - left} dp wide", right - left >= 36)
        }
    }

    private val pictogramName = Regex(".*_icon_.*24dp\\.xml|file_.*_icon\\.xml|(edit|notification|open_as)_icon\\.xml")

    // Logos and letter marks that Material Symbols do not have.
    private val ownGlyphs = setOf(
        "github_icon_white_24dp.xml", "qq_icon_white_24dp.xml", "tim_icon_white_24dp.xml",
        "twitter_icon_white_24dp.xml", "wechat_icon_white_24dp.xml", "file_excel_icon.xml",
        "file_pdf_icon.xml", "file_powerpoint_icon.xml", "file_word_icon.xml"
    )

    private fun pictograms(): List<File> =
        resources.listFiles { file -> file.name.startsWith("drawable") }!!
            .flatMap { it.listFiles { file -> pictogramName.matches(file.name) }!!.toList() }

    // Pictograms come from one set (Material Symbols, 960 grid, written by
    // utils/material-symbols.py), so a new icon in another style fails here.
    @Test
    fun pictogramsComeFromOneSet() {
        val pictograms = pictograms()
        assertTrue(pictograms.size > 80)
        for (file in pictograms.filter { it.name !in ownGlyphs }) {
            val vector = parse(file)
            assertEquals("${file.path} is not from the icon set", 960.0, vector.number("viewportWidth", 0.0), 0.0)
            assertEquals(file.path, 960.0, vector.number("viewportHeight", 0.0), 0.0)
        }
    }

    // Colors come from the tint or a color resource, so icons work in the light and the dark theme.
    @Test
    fun pictogramsCarryNoFixedColor() {
        for (file in pictograms()) {
            val vector = parse(file)
            assertEquals(file.path, 24.0, vector.getAttribute("android:width").removeSuffix("dp").toDouble(), 0.0)
            for (path in vector.paths()) {
                assertTrue(file.path, path.getAttribute("android:fillColor").startsWith("@"))
            }
        }
    }
}
