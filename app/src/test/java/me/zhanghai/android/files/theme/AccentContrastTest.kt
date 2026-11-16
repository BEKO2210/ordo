/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The five accents must be readable in light and dark: WCAG 2.2 contrast ratios, computed from the colour values
 * the accent overlays really use (read from the resource XML of the repository, @color references followed,
 * values-night on top of values for dark). docs/BARRIEREFREIHEIT.md lists what this covers and what it does not.
 *
 * - 1.4.3 text (4.5:1): accent as text, onPrimary on primary, onPrimaryContainer on primaryContainer,
 *   the snackbar action (primaryInverse) on the inverse surface.
 * - 1.4.11 non-text (3:1): the accent as progress bar, switch, ring, icon against page and card backgrounds.
 */
class AccentContrastTest {
    private val repo = File(System.getProperty("user.dir")!!).let { if (File(it, "README.md").exists()) it else it.parentFile!! }
    private val res = File(repo, "app/src/main/res")

    private class Check(val theme: String, val accent: String, val what: String, val ratio: Double, val min: Double) {
        val ok get() = ratio >= min
        override fun toString() = String.format(
            Locale.ROOT, "%-5s %-7s %-4s %-60s %6.2f:1 (min %.1f)", theme, accent, if (ok) "ok" else "FAIL", what, ratio, min
        )
    }

    private val accents = listOf("Aurora", "Blue", "Mint", "Violet", "Amber")

    @Test
    fun accentsMeetWcagContrastInLightAndDark() {
        val checks = allChecks()
        val failed = checks.filter { !it.ok }
        println(checks.joinToString("\n"))
        assertTrue(
            "${failed.size} of ${checks.size} accent colour pairs miss WCAG 2.2:\n" + checks.joinToString("\n"),
            failed.isEmpty()
        )
    }

    @Test
    fun overlaysDefineTheWholeColourFamilyForEveryAccent() {
        val overlays = overlays()
        assertEquals(accents, overlays.keys.toList())
        for ((accent, items) in overlays) {
            for (name in listOf("colorPrimary", "colorOnPrimary", "colorPrimaryContainer", "colorOnPrimaryContainer", "colorPrimaryInverse")) {
                assertTrue("$accent overlay lacks $name", items.containsKey(name))
            }
        }
    }

    @Test
    fun accentScreensTakeTheAccentFromTheTheme() {
        val allowed = listOf("/ai/", "command_sheet.xml", "onboarding_activity.xml", "OnboardingActivity.kt", "ai_colors.xml")
        val offenders = listOf("java", "res").map { File(repo, "app/src/main/$it") }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension in setOf("kt", "xml") }.toList() }
            .filter { f -> allowed.none { f.path.replace('\\', '/').contains(it) } }
            .filter { f -> Regex("ai_accent|ai_glow_|ai_on_accent").containsMatchIn(f.readText()) }
            .map { it.relativeTo(repo).path }
        assertTrue("These files use the fixed AI colours instead of ?colorPrimary: $offenders", offenders.isEmpty())
    }

    private fun allChecks(): List<Check> {
        val overlays = overlays()
        val checks = mutableListOf<Check>()
        for (night in listOf(false, true)) {
            val colors = colors(night)
            val theme = if (night) "dark" else "light"
            val backgrounds = backgrounds(colors, night)
            for ((accent, items) in overlays) {
                fun item(name: String) = parse(colors.resolve(items.getValue(name)))
                val primary = item("colorPrimary")
                for ((name, background) in backgrounds) {
                    checks += Check(theme, accent, "text: accent on $name", contrast(primary, background), 4.5)
                    checks += Check(theme, accent, "non-text: accent on $name", contrast(primary, background), 3.0)
                }
                checks += Check(theme, accent, "onPrimary on primary", contrast(item("colorOnPrimary"), primary), 4.5)
                checks += Check(
                    theme, accent, "onPrimaryContainer on primaryContainer",
                    contrast(item("colorOnPrimaryContainer"), item("colorPrimaryContainer")), 4.5
                )
                checks += Check(
                    theme, accent, "snackbar action: primaryInverse on inverse surface",
                    contrast(item("colorPrimaryInverse"), parse(colors.resolve("@color/ordo_surface_inverse"))), 4.5
                )
            }
        }
        return checks
    }

    /** Page and card backgrounds of the Material 3 and the Material Components family, as the themes set them. */
    private fun backgrounds(colors: Map<String, String>, night: Boolean): List<Pair<String, Int>> {
        fun c(name: String) = parse(colors.resolve("@color/$name"))
        val surface = c("ordo_surface")
        val glass = colors.resolve("@color/ai_card")
        val list = mutableListOf(
            "M3 page (colorSurface)" to surface,
            "M3 card (container lowest)" to c("ordo_surface_container_lowest"),
            "M3 card (container low)" to c("ordo_surface_container_low"),
            "M3 card (container)" to c("ordo_surface_container"),
            "M3 card (container high)" to c("ordo_surface_container_high"),
            "M3 card (container highest)" to c("ordo_surface_container_highest"),
            "MC page (color_surface)" to c("color_surface"),
            "dashboard glass card on M3 page" to composite(glass, surface)
        )
        if (night) {
            val black = parse("#FF000000")
            list += "black mode page" to black
            for (step in listOf("lowest", "low", "", "high", "highest")) {
                val name = "ordo_black_surface_container" + if (step.isEmpty()) "" else "_$step"
                list += "black mode card ($name)" to c(name)
            }
            list += "dashboard glass card on black" to composite(glass, black)
        }
        return list
    }

    private fun overlays(): Map<String, Map<String, String>> {
        val doc = parseXml(File(res, "values/accent_overlays.xml"))
        val result = linkedMapOf<String, Map<String, String>>()
        val styles = doc.getElementsByTagName("style")
        for (i in 0 until styles.length) {
            val style = styles.item(i) as org.w3c.dom.Element
            val name = style.getAttribute("name")
            if (!name.startsWith("ThemeOverlay.Ordo.Accent.")) continue
            val items = linkedMapOf<String, String>()
            val children = style.getElementsByTagName("item")
            for (j in 0 until children.length) {
                val child = children.item(j) as org.w3c.dom.Element
                items[child.getAttribute("name")] = child.textContent.trim()
            }
            result[name.removePrefix("ThemeOverlay.Ordo.Accent.")] = items
        }
        return result
    }

    /** All <color> entries of values/, with values-night/ on top for dark. */
    private fun colors(night: Boolean): Map<String, String> {
        val map = linkedMapOf<String, String>()
        for (dir in if (night) listOf("values", "values-night") else listOf("values")) {
            val files = File(res, dir).listFiles { f -> f.extension == "xml" }!!.sortedBy { it.name }
            for (file in files) {
                val nodes = parseXml(file).getElementsByTagName("color")
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i) as org.w3c.dom.Element
                    map[node.getAttribute("name")] = node.textContent.trim()
                }
            }
        }
        return map
    }

    private fun parseXml(file: File) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    private fun Map<String, String>.resolve(value: String): String {
        var result = value
        var depth = 0
        while (result.startsWith("@")) {
            result = when (result) {
                "@android:color/white" -> "#FFFFFFFF"
                "@android:color/black" -> "#FF000000"
                "@android:color/transparent" -> "#00000000"
                else -> {
                    require(result.startsWith("@color/")) { "Unsupported reference $result" }
                    this[result.removePrefix("@color/")] ?: error("Unknown colour $result")
                }
            }
            require(++depth < 20) { "Reference loop at $value" }
        }
        return result
    }

    /** #RGB, #RRGGBB or #AARRGGBB to opaque ARGB; an alpha channel is dropped (see composite). */
    private fun parse(hex: String): Int {
        var digits = hex.removePrefix("#")
        if (digits.length == 3) digits = digits.map { "$it$it" }.joinToString("")
        require(digits.length == 6 || digits.length == 8) { "Bad colour $hex" }
        return 0xFF000000.toInt() or digits.takeLast(6).toInt(16)
    }

    /** A resolved #AARRGGBB colour laid over an opaque background. */
    private fun composite(foreground: String, background: Int): Int {
        val digits = foreground.removePrefix("#")
        val alpha = if (digits.length == 8) digits.take(2).toInt(16) / 255.0 else 1.0
        val color = parse(foreground)
        fun channel(shift: Int): Int {
            val fg = (color shr shift) and 0xFF
            val bg = (background shr shift) and 0xFF
            return Math.round(fg * alpha + bg * (1 - alpha)).toInt()
        }
        return 0xFF000000.toInt() or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    // https://www.w3.org/TR/WCAG22/#dfn-relative-luminance
    private fun luminance(color: Int): Double {
        fun linear(shift: Int): Double {
            val srgb = ((color shr shift) and 0xFF) / 255.0
            return if (srgb <= 0.04045) srgb / 12.92 else Math.pow((srgb + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }

    // https://www.w3.org/TR/WCAG22/#dfn-contrast-ratio
    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
