/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import java8.nio.file.Paths
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AudioQueueRequestsTest {
    private val request = AudioQueueRequests.Request(listOf(Paths.get("/Music/one.mp3")), 0)

    @Test
    fun registeredRequestIsFoundByItsToken() {
        val token = AudioQueueRequests.register(request)
        assertSame(request, AudioQueueRequests.from(token))
        AudioQueueRequests.release(token)
    }

    @Test
    fun everyRegistrationGetsItsOwnToken() {
        val first = AudioQueueRequests.register(request)
        val second = AudioQueueRequests.register(request)
        assertNotEquals(first, second)
        AudioQueueRequests.release(first)
        AudioQueueRequests.release(second)
    }

    @Test
    fun releasedAndUnknownTokensAreVoid() {
        val token = AudioQueueRequests.register(request)
        AudioQueueRequests.release(token)
        assertNull(AudioQueueRequests.from(token))
        assertNull(AudioQueueRequests.from("not-a-token"))
        assertNull(AudioQueueRequests.from(null))
        AudioQueueRequests.release(null)
    }
}
