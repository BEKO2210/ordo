/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.sound

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDeviceDelayTest {
    private val a2dp = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
    private val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    private val wired = AudioDeviceInfo.TYPE_WIRED_HEADPHONES

    @Test
    fun keyNeverHoldsTheBluetoothAddress() {
        val mac = "AA:BB:CC:11:22:33"
        val key = VideoDeviceDelay.key(a2dp, "WH-1000XM5", mac)
        assertFalse(key.contains("AA:BB", ignoreCase = true))
        assertFalse(key.contains("112233"))
        assertTrue(key.startsWith("$a2dp|WH-1000XM5|"))
        // Same model, other headphones: their own key; same address in other case: the same.
        assertNotEquals(key, VideoDeviceDelay.key(a2dp, "WH-1000XM5", "AA:BB:CC:11:22:34"))
        assertEquals(key, VideoDeviceDelay.key(a2dp, "WH-1000XM5", mac.lowercase()))
    }

    @Test
    fun wiredAndSpeakerKeysIgnoreTheAddress() {
        assertEquals("$wired|USB-C", VideoDeviceDelay.key(wired, "USB-C", "card=1;device=0"))
        assertEquals("$speaker|SM-X200", VideoDeviceDelay.key(speaker, "SM-X200", ""))
        assertEquals("$a2dp|Buds", VideoDeviceDelay.key(a2dp, "Buds", null))
        assertEquals("$speaker|", VideoDeviceDelay.key(speaker, null, null))
    }

    @Test
    fun savedPerDeviceAndLoadedOnRouteChange() {
        val store = HashMap<String, Int>()
        val delay = VideoDeviceDelay(store::get) { key, value -> store[key] = value }
        val speakerKey = VideoDeviceDelay.key(speaker, "SM-X200", null)
        val phonesKey = VideoDeviceDelay.key(a2dp, "Buds", "AA:BB:CC:11:22:33")

        assertFalse("the speaker starts at 0", delay.route(speakerKey))
        assertEquals(0, delay.delayMs)
        assertFalse("the same route again changes nothing", delay.route(speakerKey))

        assertFalse("new headphones start at 0", delay.route(phonesKey))
        delay.set(200)
        assertEquals(200, store[phonesKey])

        assertTrue("back to the speaker: its 0", delay.route(speakerKey))
        assertEquals(0, delay.delayMs)
        assertTrue("headphones again: their 200 ms come back", delay.route(phonesKey))
        assertEquals(200, delay.delayMs)

        delay.set(0)
        assertEquals(0, store[phonesKey])
        assertEquals(setOf(phonesKey), store.keys)
    }

    @Test
    fun unknownOutputIsZeroAndNotStored() {
        val store = HashMap<String, Int>()
        val delay = VideoDeviceDelay(store::get) { key, value -> store[key] = value }
        delay.route(null)
        delay.set(150)
        assertEquals(150, delay.delayMs)
        assertTrue(store.isEmpty())
        // A known device after that brings its own value, not the unknown one's.
        assertTrue(delay.route("x"))
        assertEquals(0, delay.delayMs)
    }
}
