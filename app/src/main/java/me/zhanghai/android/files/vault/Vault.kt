/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.vault

import androidx.core.util.AtomicFile
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.integration.android.AndroidKeystore
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import me.zhanghai.android.files.app.application

/**
 * Files the user locked away. They are encrypted with a key that only this app on this device can
 * use, and live in app storage that is excluded from backups.
 */
object Vault {
    private const val KEYSTORE_ALIAS = "ordo_vault"
    private val KEYSET_ASSOCIATED_DATA = "ordo_vault_keyset".toByteArray()

    // lazy() runs again after a failure, so a keystore hiccup does not stick until restart.
    val store: VaultStore by lazy {
        VaultStore(File(application.noBackupFilesDir, "vault"), loadOrCreateAead())
    }

    private val openDirectory: File
        get() = File(application.cacheDir, "vault-open")

    // shortcut: the key is not bound to user authentication, the lock is enforced by the vault
    // screen. A bound key would be destroyed when the user removes the screen lock, losing every
    // file; upgrade once there is an export or recovery path.
    @Throws(IOException::class)
    private fun loadOrCreateAead(): StreamingAead {
        try {
            StreamingAeadConfig.register()
            val keysetFile = AtomicFile(File(application.noBackupFilesDir, "vault.keyset"))
            val keyset = try {
                keysetFile.readFully()
            } catch (e: FileNotFoundException) {
                null
            }
            val handle = if (keyset != null) {
                TinkProtoKeysetFormat.parseEncryptedKeyset(
                    keyset, AndroidKeystore.getAead(KEYSTORE_ALIAS), KEYSET_ASSOCIATED_DATA
                )
            } else {
                // Never reached while a keyset exists: a new key cannot open the old files.
                if (!AndroidKeystore.hasKey(KEYSTORE_ALIAS)) {
                    AndroidKeystore.generateNewAes256GcmKey(KEYSTORE_ALIAS)
                }
                KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB)
                    .also {
                        val bytes = TinkProtoKeysetFormat.serializeEncryptedKeyset(
                            it, AndroidKeystore.getAead(KEYSTORE_ALIAS), KEYSET_ASSOCIATED_DATA
                        )
                        val stream = keysetFile.startWrite()
                        try {
                            stream.write(bytes)
                            keysetFile.finishWrite(stream)
                        } catch (e: IOException) {
                            keysetFile.failWrite(stream)
                            throw e
                        }
                    }
            }
            return handle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        } catch (e: GeneralSecurityException) {
            throw IOException(e)
        }
    }

    /** Decrypts into the app's private cache so a viewer can read it; cleared when locking. */
    @Throws(IOException::class)
    fun decryptToCache(entry: VaultEntry): File {
        val directory = File(openDirectory, entry.id)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create $directory")
        }
        val file = File(directory, entry.name)
        try {
            store.open(entry).use { input ->
                file.outputStream().use { input.copyInterruptiblyTo(it) }
            }
        } catch (e: IOException) {
            file.delete()
            throw e
        }
        return file
    }

    fun clearOpenedFiles() {
        openDirectory.deleteRecursively()
    }

    /** Removes what a killed process left behind: opened copies and half-written entries. */
    fun cleanUp() {
        clearOpenedFiles()
        // Without a vault there is nothing to purge, and no reason to create a key yet.
        if (File(application.noBackupFilesDir, "vault").isDirectory) {
            try {
                store.purgeLeftovers()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}
