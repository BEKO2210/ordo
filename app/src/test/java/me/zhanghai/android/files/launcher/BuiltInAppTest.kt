/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.launcher

import android.content.pm.PackageManager
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class BuiltInAppTest {
    private val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml")).documentElement

    private fun Element.children(tag: String): List<Element> =
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun Element.attr(name: String): String = getAttribute("android:$name")

    @Test
    fun onlyTheMusicPlayerIsReadyAndTheOthersAreAnnounced() {
        assertEquals(listOf(BuiltInApp.MUSIC), BuiltInApp.available)
        assertEquals(
            listOf(BuiltInApp.VIDEO, BuiltInApp.IMAGES, BuiltInApp.PDF, BuiltInApp.NOTES),
            BuiltInApp.entries.filter { !it.isAvailable }
        )
    }

    @Test
    fun noIconIsOnByDefaultAndTheSystemDefaultMeansOff() {
        assertFalse(BuiltInApp.ENABLED_BY_DEFAULT)
        assertFalse(BuiltInApp.isEnabled(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT))
        assertFalse(BuiltInApp.isEnabled(PackageManager.COMPONENT_ENABLED_STATE_DISABLED))
        assertFalse(BuiltInApp.isEnabled(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER))
        assertTrue(BuiltInApp.isEnabled(PackageManager.COMPONENT_ENABLED_STATE_ENABLED))
    }

    @Test
    fun theManifestDeclaresExactlyTheAvailableAliasesDisabled() {
        val aliases = manifest.children("activity-alias")
        assertEquals(
            BuiltInApp.available.mapNotNull { it.aliasName }.sorted(),
            aliases.map { it.attr("name") }.sorted()
        )
        val activities = manifest.children("activity").map { it.attr("name") }.toSet()
        for (alias in aliases) {
            assertEquals(alias.attr("name"), "false", alias.attr("enabled"))
            assertTrue(alias.attr("name"), alias.attr("targetActivity") in activities)
            assertTrue(alias.attr("name"), alias.attr("icon").startsWith("@mipmap/"))
            val categories = alias.children("category").map { it.attr("name") }
            assertTrue(alias.attr("name"), "android.intent.category.LAUNCHER" in categories)
        }
    }

    @Test
    fun everyAliasLabelIsAStringOfItsApp() {
        val strings = File("src/main/res/values/settings_ordo_strings.xml").readText()
        for (app in BuiltInApp.entries) {
            assertTrue(app.name, Regex("""name="launcher_${app.name.lowercase()}_label"""").containsMatchIn(strings))
        }
    }
}
