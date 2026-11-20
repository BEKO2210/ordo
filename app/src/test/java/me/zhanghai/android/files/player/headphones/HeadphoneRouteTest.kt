/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.headphones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadphoneRouteTest {
    private val isHeadphones: (String) -> Boolean = { it.startsWith("phones") }

    @Test
    fun connectedButUnusedHeadphonesDoNotCountWhileMediaPlaysOnTheSpeaker() {
        assertNull(HeadphoneOutput.routedOrConnected(listOf("speaker"), { listOf("speaker", "phonesBt") }, isHeadphones))
    }

    @Test
    fun theRoutedHeadphonesCount() {
        assertEquals(
            "phonesUsb",
            HeadphoneOutput.routedOrConnected(listOf("phonesUsb"), { listOf("phonesBt", "phonesUsb") }, isHeadphones)
        )
    }

    @Test
    fun unknownRouteFallsBackToAnyConnectedHeadphones() {
        assertEquals("phonesBt", HeadphoneOutput.routedOrConnected(null, { listOf("speaker", "phonesBt") }, isHeadphones))
        assertEquals(
            "phonesBt", HeadphoneOutput.routedOrConnected(emptyList(), { listOf("speaker", "phonesBt") }, isHeadphones)
        )
    }
}
