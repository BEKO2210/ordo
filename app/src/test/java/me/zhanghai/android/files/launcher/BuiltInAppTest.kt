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

    private val prefixes = listOf(IconAliases.HOME) + BuiltInApp.available.mapNotNull { it.aliasPrefix }

    @Test
    fun theManifestDeclaresOneAliasPerIconAndColourAndOnlyOrdosBlueIsOn() {
        val aliases = manifest.children("activity-alias")
        assertEquals(
            prefixes.flatMap { prefix -> IconColor.entries.map { IconAliases.name(prefix, it) } }.sorted(),
            aliases.map { it.attr("name") }.sorted()
        )
        val activities = manifest.children("activity").map { it.attr("name") }.toSet()
        for (alias in aliases) {
            val name = alias.attr("name")
            // No android:enabled means enabled: after installing only Ordo's blue icon may be on.
            assertEquals(name, IconAliases.enabledByDefault(name), alias.attr("enabled") != "false")
            assertTrue(name, alias.attr("targetActivity") in activities)
            val icon = alias.attr("icon").removePrefix("@mipmap/")
            // Every colour is an adaptive icon with a monochrome layer, so themed icons (Android 13+) keep working.
            val adaptive = File("src/main/res/mipmap-anydpi-v26/$icon.xml")
            val adaptiveXml = adaptive.readText()
            assertTrue("$name: $adaptive", adaptiveXml.contains("<monochrome"))
            // The background is a plain rectangle filling the whole 108 dp canvas, like the blue original: no size,
            // padding or inset that would leave a lighter edge for the launcher's mask to show.
            val background = Regex("""<background android:drawable="@drawable/(\w+)"""").find(adaptiveXml)!!.groupValues[1]
            for (folder in listOf("drawable", "drawable-v24")) {
                val shape = File("src/main/res/$folder/$background.xml").takeIf { it.exists() }?.readText() ?: continue
                assertTrue("$name: $folder/$background", shape.contains("android:shape=\"rectangle\""))
                assertFalse("$name: $folder/$background", Regex("<size|<padding|<inset|<stroke").containsMatchIn(shape))
            }
            val categories = alias.children("category").map { it.attr("name") }
            assertTrue(name, "android.intent.category.LAUNCHER" in categories)
        }
        // Ordo's own activity is no launcher entry itself any more: every colour is an alias.
        val launcherActivities = manifest.children("activity").filter { activity ->
            activity.children("category").any { it.attr("name") == "android.intent.category.LAUNCHER" }
        }
        assertEquals(emptyList<String>(), launcherActivities.map { it.attr("name") })
    }

    @Test
    fun blueComesFirstAndEveryColourHasItsSwatch() {
        assertEquals(IconColor.BLUE, IconColor.entries.first())
        assertEquals(8, IconColor.entries.size)
        val palette = File("src/main/res/values/launcher_icon_palette.xml").readText()
        for (color in IconColor.entries) {
            assertTrue(color.name, palette.contains("launcher_icon_${color.name.lowercase()}\""))
        }
    }

    @Test
    fun choosingAColourLeavesExactlyThatAliasOnAndNeverNone() {
        for (prefix in prefixes) {
            // Every starting point: each colour on, none on (an app switched off), and a broken state with two on.
            val starts = IconColor.entries.map { setOf(IconAliases.name(prefix, it)) } + listOf(
                emptySet(), setOf(IconAliases.name(prefix, IconColor.RED), IconAliases.name(prefix, IconColor.BLUE))
            )
            for (start in starts) {
                for (color in IconColor.entries) {
                    val on = start.toMutableSet()
                    val switches = IconAliases.switchesFor(prefix, color) { it in on }
                    for ((alias, enable) in switches) {
                        if (enable) on += alias else on -= alias
                        assertTrue("$start -> $color: no entry after $alias", start.isEmpty() || on.isNotEmpty())
                    }
                    assertEquals("$start -> $color", setOf(IconAliases.name(prefix, color)), on)
                    assertEquals(color, IconAliases.current(prefix) { it in on })
                    if (IconAliases.name(prefix, color) in start) {
                        assertTrue("$start -> $color switches nothing on", switches.none { it.second })
                    } else {
                        assertEquals(IconAliases.name(prefix, color) to true, switches.first())
                    }
                }
                val on = start.toMutableSet()
                IconAliases.switchesOff(prefix) { it in on }.forEach { (alias, _) -> on -= alias }
                assertEquals(emptySet<String>(), on)
                assertEquals(null, IconAliases.current(prefix) { it in on })
            }
        }
    }

    @Test
    fun aSavedColourThatNeverReachedTheHomeScreenIsAppliedLater() {
        // Ordo was killed before going to the background: saved green, blue still enabled -> apply at ON_STOP.
        assertTrue(IconAliases.needsReconcile(IconColor.GREEN, IconColor.BLUE))
        // Saved and enabled agree -> nothing to do.
        for (color in IconColor.entries) assertFalse(IconAliases.needsReconcile(color, color))
        // Nothing saved yet, or the icon is switched off (no alias enabled) -> leave it alone.
        assertFalse(IconAliases.needsReconcile(null, IconColor.BLUE))
        assertFalse(IconAliases.needsReconcile(IconColor.GREEN, null))
    }

    @Test
    fun onlyOrdosBlueIconIsOnByDefault() {
        assertTrue(IconAliases.enabledByDefault("me.zhanghai.android.files.launcher.HomeAlias"))
        assertFalse(IconAliases.enabledByDefault("me.zhanghai.android.files.launcher.HomeGreen"))
        assertFalse(IconAliases.enabledByDefault("me.zhanghai.android.files.launcher.MusicAlias"))
        assertTrue(BuiltInApp.isEnabled(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, byDefault = true))
        assertFalse(BuiltInApp.isEnabled(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, byDefault = true))
    }

    @Test
    fun everyAliasLabelIsAStringOfItsApp() {
        val strings = File("src/main/res/values/settings_ordo_strings.xml").readText()
        for (app in BuiltInApp.entries) {
            assertTrue(app.name, Regex("""name="launcher_${app.name.lowercase()}_label"""").containsMatchIn(strings))
        }
    }
}
