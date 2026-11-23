/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.provider.sftp.client

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientTimeoutTest {
    @Test
    fun sessionTimeoutIsNotTheConnectTimeout() {
        val open = File("src/main/java/me/zhanghai/android/files/provider/sftp/client/Client.kt")
            .readText()
            .substringAfter("SSHClient().apply {")
            .substringBefore("try {")
        assertTrue(open.contains("connectTimeout = CONNECT_TIMEOUT_MILLIS"))
        assertTrue(open.contains("timeout = 0"))
    }

    @Test
    fun cancelDisconnectsTheThreadSessionAndDropsADeadTransport() {
        val client = File("src/main/java/me/zhanghai/android/files/provider/sftp/client/Client.kt")
            .readText()
        assertTrue(client.contains("fun disconnectThread"))
        assertTrue(client.contains("fun releaseThread"))
        assertTrue(client.contains("fun noteTransportError"))
        val channel = File("src/main/java/me/zhanghai/android/files/provider/sftp/client/FileByteChannel.kt")
            .readText()
        assertTrue(channel.contains("onTransportError"))
        assertTrue(channel.contains("this !is SFTPException"))
    }
}
