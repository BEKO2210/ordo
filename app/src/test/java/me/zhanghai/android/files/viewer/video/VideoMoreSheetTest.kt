/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

class VideoMoreSheetTest {
    @Test
    fun entriesGoToTheirCardInCardOrderAndKeepTheirOrder() {
        val items = listOf("info" to 3, "sound" to 1, "chapters" to 2, "track" to 1, "stray" to 9)
        val grouped = VideoMoreSheet.grouped(items, listOf(1, 2, 3)) { it.second }
        assertEquals(listOf(0, 1, 2), grouped.map { it.first })
        assertEquals(listOf("sound", "track"), grouped[0].second.map { it.first })
        assertEquals(listOf("chapters"), grouped[1].second.map { it.first })
        // An entry of an unknown group is never lost: it joins the last card.
        assertEquals(listOf("info", "stray"), grouped[2].second.map { it.first })
    }

    @Test
    fun emptyCardsAreLeftOut() {
        val grouped = VideoMoreSheet.grouped(listOf("a" to 3), listOf(1, 2, 3)) { it.second }
        assertEquals(listOf(2), grouped.map { it.first })
    }

    /** The top bar keeps three icons; every other action lives in the sheet, each with a symbol for its card. */
    @Test
    fun theTopBarHoldsCastSubtitlesAndMoreAndEveryMoreEntryHasAnIcon() {
        fun items(name: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/menu/$name.xml")).getElementsByTagName("item")
            .let { list -> (0 until list.length).map { list.item(it) as Element } }
        assertEquals(
            listOf("@+id/actionCast", "@+id/actionSubtitles", "@+id/actionMore"),
            items("video_player").map { it.getAttribute("android:id") }
        )
        val more = items("video_player_more")
        assertEquals(emptyList<String>(), more.filter { it.getAttribute("android:icon").isEmpty() }.map { it.getAttribute("android:id") })
        assertEquals(
            setOf("actionSound", "actionAudioTrack", "actionSubtitles", "actionPictureAdjust", "actionPictureEnhance",
                "actionSignalPath", "actionSpeed", "actionSleepTimer", "actionPip", "actionFileInfo", "actionOpenWith"),
            more.map { it.getAttribute("android:id").removePrefix("@+id/") }.toSet()
        )
    }
}
