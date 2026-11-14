/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.vault

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun newAead(): StreamingAead {
        StreamingAeadConfig.register()
        return KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB)
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }

    private fun newStore(aead: StreamingAead = newAead()) = VaultStore(folder.root, aead)

    // Larger than one segment, so segment boundaries are covered.
    private val content = Random(1).nextBytes(10_000)

    @Test
    fun add_roundTrip() {
        val store = newStore()
        val entry = store.add("/storage/emulated/0/DCIM/Urlaub ä.jpg", content.inputStream(), 42)
        assertEquals("Urlaub ä.jpg", entry.name)
        assertEquals(10_000, entry.size)
        val listed = store.entries().single()
        assertEquals(entry.id, listed.id)
        assertEquals("/storage/emulated/0/DCIM/Urlaub ä.jpg", listed.originalPath)
        assertEquals(10_000, listed.size)
        assertEquals(42, listed.addedAt)
        assertArrayEquals(content, store.open(listed).use { it.readBytes() })
    }

    @Test
    fun add_storesNoPlaintext() {
        val store = newStore()
        val entry = store.add("/storage/emulated/0/geheim.txt", "streng geheim".repeat(50).byteInputStream())
        val stored = folder.root.listFiles()!!.joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }
        assertFalse(stored.contains("geheim"))
        assertEquals(setOf(entry.id, entry.id + VaultStore.INFO_SUFFIX), folder.root.list()!!.toSet())
    }

    @Test
    fun open_rejectsTamperedContent() {
        val store = newStore()
        val entry = store.add("/a/b.bin", content.inputStream())
        val file = File(folder.root, entry.id)
        val bytes = file.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        file.writeBytes(bytes)
        assertThrows(IOException::class.java) { store.open(entry).use { it.readBytes() } }
    }

    @Test
    fun open_rejectsTruncatedContent() {
        val store = newStore()
        val entry = store.add("/a/b.bin", content.inputStream())
        val file = File(folder.root, entry.id)
        file.writeBytes(file.readBytes().copyOf(5_000))
        assertThrows(IOException::class.java) { store.open(entry).use { it.readBytes() } }
    }

    @Test
    fun open_rejectsSwappedFiles() {
        val store = newStore()
        val first = store.add("/a/first.bin", content.inputStream())
        val second = store.add("/a/second.bin", ByteArray(100).inputStream())
        File(folder.root, second.id).copyTo(File(folder.root, first.id), true)
        assertThrows(IOException::class.java) { store.open(first).use { it.readBytes() } }
    }

    @Test
    fun entries_skipsForeignKey() {
        newStore().add("/a/b.bin", content.inputStream())
        assertTrue(newStore().entries().isEmpty())
    }

    @Test
    fun add_cleansUpWhenInterrupted() {
        val store = newStore()
        Thread.currentThread().interrupt()
        assertThrows(InterruptedIOException::class.java) { store.add("/a/b.bin", content.inputStream()) }
        assertTrue(folder.root.list()!!.isEmpty())
    }

    @Test
    fun delete_removesEntry() {
        val store = newStore()
        val entry = store.add("/a/b.bin", content.inputStream())
        assertTrue(store.delete(entry))
        assertTrue(store.entries().isEmpty())
        assertTrue(folder.root.list()!!.isEmpty())
    }

    @Test
    fun purgeLeftovers_keepsEntriesAndRunningAdds() {
        val store = newStore()
        val entry = store.add("/a/b.bin", content.inputStream())
        val oldTemp = File(folder.root, "1-old" + VaultStore.TEMP_SUFFIX).apply { writeText("x") }
        val oldInfo = File(folder.root, "1-old" + VaultStore.INFO_SUFFIX).apply { writeText("x") }
        val now = System.currentTimeMillis()
        store.purgeLeftovers(now)
        assertTrue(oldTemp.exists() && oldInfo.exists())
        store.purgeLeftovers(now + VaultStore.LEFTOVER_AGE_MILLIS + 60_000)
        assertFalse(oldTemp.exists() || oldInfo.exists())
        assertEquals(entry.id, store.entries().single().id)
    }
}
