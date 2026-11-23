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
    fun clientSetsTimeoutsAndDropsABrokenTransfer() {
        val text = File("src/main/java/me/zhanghai/android/files/provider/ftp/client/Client.kt")
            .readText()
        assertTrue(text.contains("setConnectTimeout("))
        assertTrue(text.contains("setDefaultTimeout("))
        assertTrue(text.contains("setDataTimeout("))
        assertTrue(text.contains("if (keep) releaseClient"))
        assertTrue(text.contains("else closeQuietly"))
        assertFalse(text.contains("ftpPooledClientStale"))
        assertFalse(text.contains("ftp-client-pool"))
    }
}
