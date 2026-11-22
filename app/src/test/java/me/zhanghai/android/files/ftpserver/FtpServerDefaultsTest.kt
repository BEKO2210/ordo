/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ftpserver

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class FtpServerDefaultsTest {
    private val prefs = File("src/main/res/values/donottranslate_prefs.xml")
    private val service = File("src/main/java/me/zhanghai/android/files/ftpserver/FtpServerService.kt")

    private fun bool(name: String): Boolean {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(prefs).getElementsByTagName("bool")
        val element = (0 until nodes.length).map { nodes.item(it) as Element }
            .single { it.getAttribute("name") == name }
        return element.textContent.trim().toBoolean()
    }

    @Test
    fun anonymousLoginAndWritingAreOffByDefault() {
        assertFalse(bool("pref_default_value_ftp_server_anonymous_login"))
        assertFalse(bool("pref_default_value_ftp_server_writable"))
    }

    @Test
    fun killedServerDoesNotRestartItself() {
        val source = service.readText()
        assertTrue(source.contains("START_NOT_STICKY"))
        assertFalse(
            Regex("""onStartCommand\([^)]*\):\s*Int\s*=\s*START_STICKY""").containsMatchIn(source)
        )
    }

    @Test
    fun listFilesClosesTheDirectoryStream() {
        val listFiles = File("src/main/java/me/zhanghai/android/files/ftpserver/ProviderFtpFile.kt")
            .readText()
            .substringAfter("override fun listFiles()")
            .substringBefore("override fun createOutputStream")
        assertTrue(listFiles.contains("newDirectoryStream().use"))
    }
}
