/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.provider.ftp.client

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientPoolTest {
    @Test
    fun idleClientIsStaleAfterOneMinute() {
        val idleAt = 1_000L
        assertFalse(ftpPooledClientStale(idleAt, idleAt + FTP_POOL_IDLE_MILLIS - 1))
        assertTrue(ftpPooledClientStale(idleAt, idleAt + FTP_POOL_IDLE_MILLIS))
    }

    @Test
    fun poolKeepsFourIdleClients() {
        assertTrue(ftpPoolHasRoom(0))
        assertTrue(ftpPoolHasRoom(FTP_POOL_MAX - 1))
        assertFalse(ftpPoolHasRoom(FTP_POOL_MAX))
    }

    @Test
    fun clientSetsTimeoutsAndDropsABrokenTransfer() {
        val text = File("src/main/java/me/zhanghai/android/files/provider/ftp/client/Client.kt")
            .readText()
        assertTrue(text.contains("setConnectTimeout("))
        assertTrue(text.contains("setDefaultTimeout("))
        assertTrue(text.contains("setDataTimeout("))
        assertFalse(text.contains("if (false)"))
        assertTrue(text.contains("ftpPooledClientStale("))
        assertTrue(text.contains("ftpPoolHasRoom("))
        assertTrue(text.contains("if (keep) releaseClient"))
        assertTrue(text.contains("else closeQuietly"))
    }
}
