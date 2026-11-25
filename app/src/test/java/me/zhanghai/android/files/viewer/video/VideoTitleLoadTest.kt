/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** content:// names: looked up off the caller's thread, the playing one first, each handed over at once. */
class VideoTitleLoadTest {
    @Test
    fun playingOneFirstThenTheRestEachAtOnce() = runBlocking {
        val events = mutableListOf<String>()
        loadTitles(listOf("a", "b", "c", "b"), 1, { events += "lookup $it"; "$it.mp4" }) { id, title ->
            events += "publish $id=$title"
        }
        assertEquals(
            listOf(
                "lookup b", "publish b=b.mp4", "lookup c", "publish c=c.mp4", "lookup a", "publish a=a.mp4"
            ),
            events
        )
    }

    @Test
    fun unknownNamesKeepTheirPlaceholder() = runBlocking {
        val published = mutableListOf<String>()
        loadTitles(listOf("a", "b", "c"), -1, { if (it == "b") "b.mp4" else if (it == "c") "" else null }) { id, _ ->
            published += id
        }
        assertEquals(listOf("b"), published)
    }

    @Test
    fun lookupsLeaveTheCallingThread() = runBlocking {
        val caller = Thread.currentThread()
        var lookupThread: Thread? = null
        loadTitles(listOf("a"), 0, { lookupThread = Thread.currentThread(); "a.mp4" }) { _, _ -> }
        assertNotEquals(caller, lookupThread)
    }

    @Test
    fun cancelledScreenStopsAfterTheRunningLookup() = runBlocking {
        val inFirst = CountDownLatch(1)
        val release = CountDownLatch(1)
        val looked = mutableListOf<String>()
        val published = mutableListOf<String>()
        val job = CoroutineScope(Dispatchers.Default).launch {
            loadTitles(listOf("a", "b", "c"), 0, {
                synchronized(looked) { looked += it }
                if (it == "a") { inFirst.countDown(); release.await() }
                "$it.mp4"
            }) { id, _ -> published += id }
        }
        inFirst.await()
        job.cancel()
        release.countDown()
        job.cancelAndJoin()
        assertEquals(listOf("a"), synchronized(looked) { looked.toList() })
        assertEquals(emptyList<String>(), published)
    }
}
