/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.vault

import com.google.crypto.tink.StreamingAead
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.security.GeneralSecurityException
import java.util.Properties
import java.util.UUID

class VaultEntry(val id: String, val originalPath: String, val size: Long, val addedAt: Long) {
    val name: String
        get() = originalPath.substringAfterLast('/')
}

/**
 * Encrypted files in [directory]. Every entry is a content file named by its id plus an info file;
 * both are encrypted in segments, so large files never have to fit into memory.
 */
class VaultStore(private val directory: File, private val aead: StreamingAead) {
    @Throws(IOException::class)
    fun add(
        originalPath: String,
        input: InputStream,
        now: Long = System.currentTimeMillis()
    ): VaultEntry {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create vault in $directory")
        }
        val id = "$now-${UUID.randomUUID().toString().take(8)}"
        val tempFile = File(directory, id + TEMP_SUFFIX)
        val infoFile = File(directory, id + INFO_SUFFIX)
        var isAdded = false
        try {
            val size = encrypt(tempFile, id).use { input.copyInterruptiblyTo(it) }
            val entry = VaultEntry(id, originalPath, size, now)
            encrypt(infoFile, infoFile.name).use { formatInfo(entry).store(it, null) }
            // The entry only counts once its content has the final name.
            if (!tempFile.renameTo(File(directory, id))) {
                throw IOException("Cannot finish vault entry $id")
            }
            isAdded = true
            return entry
        } finally {
            if (!isAdded) {
                tempFile.delete()
                infoFile.delete()
            }
        }
    }

    @Throws(IOException::class)
    fun open(entry: VaultEntry): InputStream = decrypt(File(directory, entry.id), entry.id)

    /** Decrypts with random access (seeking in a video) and never writes the plaintext anywhere. */
    @Throws(IOException::class)
    fun openSeekable(id: String): SeekableByteChannel {
        val channel = FileInputStream(File(directory, id)).channel
        try {
            return aead.newSeekableDecryptingChannel(channel, id.toByteArray()).apply {
                // Tink's keyset channel picks the key on its first read; size() throws before that.
                read(ByteBuffer.allocate(1))
                position(0)
            }
        } catch (e: GeneralSecurityException) {
            channel.close()
            throw IOException(e)
        } catch (e: IOException) {
            channel.close()
            throw e
        }
    }

    fun entries(): List<VaultEntry> =
        directory.listFiles().orEmpty().mapNotNull { infoFile ->
            if (!infoFile.name.endsWith(INFO_SUFFIX)) {
                return@mapNotNull null
            }
            val id = infoFile.name.removeSuffix(INFO_SUFFIX)
            if (!File(directory, id).isFile) {
                return@mapNotNull null
            }
            try {
                val properties = Properties()
                decrypt(infoFile, infoFile.name).use { properties.load(it) }
                parseInfo(id, properties)
            } catch (e: IOException) {
                e.printStackTrace()
                null
            }
        }.sortedByDescending { it.addedAt }

    fun delete(entry: VaultEntry): Boolean =
        File(directory, entry.id).delete() && File(directory, entry.id + INFO_SUFFIX).delete()

    // A crash while adding or deleting leaves a temp or info file that no entry points to.
    fun purgeLeftovers(now: Long = System.currentTimeMillis()) {
        for (file in directory.listFiles().orEmpty()) {
            val isLeftover = file.name.endsWith(TEMP_SUFFIX) || file.name.endsWith(INFO_SUFFIX) &&
                !File(directory, file.name.removeSuffix(INFO_SUFFIX)).exists()
            // Younger files may belong to an add that is still running.
            if (isLeftover && now - file.lastModified() > LEFTOVER_AGE_MILLIS) {
                file.delete()
            }
        }
    }

    // The associated data ties the ciphertext to its file name, so files cannot be swapped.
    @Throws(IOException::class)
    private fun encrypt(file: File, associatedData: String): OutputStream {
        val output = SyncingFileOutputStream(file)
        try {
            return aead.newEncryptingStream(output, associatedData.toByteArray())
        } catch (e: GeneralSecurityException) {
            output.close()
            throw IOException(e)
        }
    }

    @Throws(IOException::class)
    private fun decrypt(file: File, associatedData: String): InputStream {
        val input = file.inputStream()
        try {
            return aead.newDecryptingStream(input, associatedData.toByteArray())
        } catch (e: GeneralSecurityException) {
            input.close()
            throw IOException(e)
        }
    }

    private fun formatInfo(entry: VaultEntry): Properties =
        Properties().apply {
            setProperty(KEY_PATH, entry.originalPath)
            setProperty(KEY_SIZE, entry.size.toString())
            setProperty(KEY_ADDED_AT, entry.addedAt.toString())
        }

    private fun parseInfo(id: String, properties: Properties): VaultEntry? {
        val path = properties.getProperty(KEY_PATH) ?: return null
        val size = properties.getProperty(KEY_SIZE)?.toLongOrNull() ?: return null
        val addedAt = properties.getProperty(KEY_ADDED_AT)?.toLongOrNull() ?: return null
        return VaultEntry(id, path, size, addedAt)
    }

    // The original is deleted right after it was added, so the copy has to be on disk by then.
    private class SyncingFileOutputStream(file: File) : FilterOutputStream(FileOutputStream(file)) {
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
        }

        override fun close() {
            (out as FileOutputStream).use {
                it.flush()
                it.fd.sync()
            }
        }
    }

    companion object {
        const val INFO_SUFFIX = ".info"
        const val TEMP_SUFFIX = ".tmp"
        const val LEFTOVER_AGE_MILLIS = 24L * 60 * 60 * 1000
        private const val KEY_PATH = "path"
        private const val KEY_SIZE = "size"
        private const val KEY_ADDED_AT = "addedAt"
    }
}

/** Like copyTo(), but a cancelled file job stops in the middle of a large file. */
@Throws(IOException::class)
fun InputStream.copyInterruptiblyTo(output: OutputStream): Long {
    val buffer = ByteArray(64 * 1024)
    var size = 0L
    while (true) {
        if (Thread.interrupted()) {
            throw InterruptedIOException()
        }
        val length = read(buffer)
        if (length < 0) {
            return size
        }
        output.write(buffer, 0, length)
        size += length
    }
}
