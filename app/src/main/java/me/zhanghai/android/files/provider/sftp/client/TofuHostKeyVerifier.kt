/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.provider.sftp.client

import android.util.Base64
import me.zhanghai.android.files.app.application
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Properties

/**
 * Trust on first use, like OpenSSH's StrictHostKeyChecking=accept-new: the first key a server shows is
 * remembered per host and port, and a different key later refuses the connection (possible
 * man-in-the-middle). Stored outside backups, next to the vault keyset.
 */
object TofuHostKeyVerifier : HostKeyVerifier {
    private val file: File
        get() = File(application.noBackupFilesDir, "sftp_known_hosts.properties")

    @Synchronized
    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val id = "$hostname:$port"
        val fingerprint = fingerprint(key)
        val known = load()
        val stored = known.getProperty(id)
        if (stored != null) {
            return stored == fingerprint
        }
        known.setProperty(id, fingerprint)
        try {
            file.outputStream().use { known.store(it, null) }
        } catch (e: IOException) {
            // Not remembered means asked again next time; the connection itself is still first-use.
            e.printStackTrace()
        }
        return true
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()

    private fun load(): Properties = Properties().apply {
        if (file.exists()) {
            try {
                file.inputStream().use { load(it) }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun fingerprint(key: PublicKey): String =
        "SHA256:" + Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(key.encoded), Base64.NO_WRAP or Base64.NO_PADDING
        )
}
