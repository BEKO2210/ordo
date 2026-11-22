/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.provider.smb.client

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientTimeoutTest {
    private val source =
        File("src/main/java/me/zhanghai/android/files/provider/smb/client/Client.kt").readText()

    @Test
    fun soTimeoutIsNotSetOnTheSocket() {
        val config = source.substringAfter("SmbConfig.builder()").substringBefore(".build()")
        assertTrue(config.contains("withTimeout(TRANSFER_TIMEOUT_SECONDS"))
        assertTrue(config.contains("withSocketFactory"))
        assertFalse(config.contains("withSoTimeout"))
        assertTrue(source.contains("TRANSFER_TIMEOUT_SECONDS = 60L"))
        assertTrue(source.contains("CONNECT_TIMEOUT_MILLIS = 15_000"))
    }
}
