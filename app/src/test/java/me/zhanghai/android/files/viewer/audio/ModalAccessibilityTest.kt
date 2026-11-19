/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * TalkBack must not reach what lies behind an open side menu or sheet. Both come from the platform, so these guard
 * the conditions it needs: AndroidX DrawerLayout marks every child that is not the open drawer
 * noHideDescendants (and back on close) only for its direct children, so the library and the menu stay its only two;
 * a sheet is a modal dialog window, which hides the windows below it from accessibility, so no player or library
 * code makes a window non-modal or unfocusable.
 */
class ModalAccessibilityTest {
    @Test
    fun theMusicSideMenuIsADrawerLayoutDrawerBesideTheLibrary() {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/layout/music_launcher_activity.xml")).documentElement
        assertEquals("androidx.drawerlayout.widget.DrawerLayout", root.tagName)
        val children = (0 until root.childNodes.length).map { root.childNodes.item(it) }.filterIsInstance<Element>()
        assertEquals(listOf("@+id/content", ""), children.map { it.getAttribute("android:id") })
        assertEquals("start", children[1].getAttribute("android:layout_gravity"))
    }

    @Test
    fun noPlayerOrLibraryWindowIsNonModal() {
        val hits = File("src/main/java/me/zhanghai/android/files/viewer").walk().filter { it.extension == "kt" }
            .filter { Regex("FLAG_NOT_TOUCH_MODAL|FLAG_NOT_FOCUSABLE").containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), hits)
    }
}
