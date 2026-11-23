/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.provider.sftp.client

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SftpSessionTableTest {
    @Test
    fun cancellingOneJobLeavesTheOtherJobAndTheSharedSession() {
        val table = SftpSessionTable<String, String>()
        val jobA = Thread("sftp-job-a")
        val jobB = Thread("sftp-job-b")
        table.bindJob(jobA)
        table.bindJob(jobB)
        table.putSharedIfAbsent("nas", "shared")
        assertTrue(table.storeJob(jobA, "nas", "job-a") is SftpJobStore.Stored)
        assertTrue(table.storeJob(jobB, "nas", "job-b") is SftpJobStore.Stored)

        val closed = table.takeJob(jobA)

        assertEquals(listOf("job-a"), closed)
        assertEquals("job-b", table.lookupJob(jobB, "nas"))
        assertEquals("shared", table.lookupShared("nas"))
        assertNull(table.lookupJob(jobA, "nas"))
        assertTrue(table.wasCancelled(jobA))
        assertFalse(table.wasCancelled(jobB))
        assertTrue(table.storeJob(jobA, "nas", "job-a-again") is SftpJobStore.Unbound)
    }

    @Test
    fun jobLookupDoesNotReturnTheSharedSession() {
        val table = SftpSessionTable<String, String>()
        val job = Thread("sftp-job")
        table.putSharedIfAbsent("nas", "shared")
        table.bindJob(job)
        assertNull(table.lookupJob(job, "nas"))
        assertEquals("shared", table.lookupShared("nas"))
    }

    @Test
    fun bindAfterCancelAllowsANewExclusiveSession() {
        val table = SftpSessionTable<String, String>()
        val thread = Thread("sftp-pool")
        table.bindJob(thread)
        table.storeJob(thread, "nas", "first")
        table.takeJob(thread)
        table.bindJob(thread)
        assertFalse(table.wasCancelled(thread))
        val stored = table.storeJob(thread, "nas", "second") as SftpJobStore.Stored
        assertNull(stored.previous)
        assertEquals("second", table.lookupJob(thread, "nas"))
    }

    @Test
    fun dropMatchingRemovesTheDeadSessionFromJobsAndShared() {
        val table = SftpSessionTable<String, String>()
        val job = Thread("sftp-job")
        table.bindJob(job)
        table.putSharedIfAbsent("nas", "dead")
        table.storeJob(job, "other", "dead")
        table.storeJob(job, "live", "ok")

        val dropped = table.dropMatching { it == "dead" }

        assertEquals(setOf("dead"), dropped.toSet())
        assertNull(table.lookupShared("nas"))
        assertNull(table.lookupJob(job, "other"))
        assertEquals("ok", table.lookupJob(job, "live"))
    }

    @Test
    fun takeJobOnTwoThreadsDoesNotShareASession() {
        val table = SftpSessionTable<String, AtomicInteger>()
        val start = CyclicBarrier(2)
        val done = CountDownLatch(2)
        val taken = CopyOnWriteArrayList<AtomicInteger>()
        fun job(name: String, session: AtomicInteger) = Thread({
            table.bindJob(Thread.currentThread())
            table.storeJob(Thread.currentThread(), "nas", session)
            start.await(2, TimeUnit.SECONDS)
            taken += table.takeJob(Thread.currentThread())
            done.countDown()
        }, name)

        val a = AtomicInteger(1)
        val b = AtomicInteger(2)
        job("sftp-a", a).start()
        job("sftp-b", b).start()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(setOf(a, b), taken.toSet())
        assertSame(a, taken.single { it.get() == 1 })
        assertSame(b, taken.single { it.get() == 2 })
    }

    @Test
    fun sftpCloseOnBackgroundDoesNotRunOnTheCaller() {
        val caller = Thread.currentThread()
        val ranOn = java.util.concurrent.atomic.AtomicReference<Thread>()
        val latch = CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            sftpCloseOnBackground(executor) {
                ranOn.set(Thread.currentThread())
                latch.countDown()
            }
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertTrue(ranOn.get() != null && ranOn.get() !== caller)
        } finally {
            executor.shutdownNow()
        }
    }
}
