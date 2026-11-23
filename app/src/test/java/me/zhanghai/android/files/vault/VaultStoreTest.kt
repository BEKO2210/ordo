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

    // What VaultProvider hands a viewer: random access decrypted in memory, nothing written next to the vault.
    @Test
    fun openSeekable_readsAnywhereWithoutWritingPlaintext() {
        val store = newStore()
        val entry = store.add("/storage/emulated/0/DCIM/a.jpg", content.inputStream())
        val before = folder.root.list()!!.toSet()
        store.openSeekable(entry.id).use { channel ->
            assertEquals(10_000, channel.size())
            val data = ByteArray(2_000)
            // Across a segment boundary, and cut short at the end of the file.
            assertEquals(1_000, VaultProvider.readAt(channel, 9_000, data, 2_000))
            assertArrayEquals(content.copyOfRange(9_000, 10_000), data.copyOf(1_000))
            assertEquals(100, VaultProvider.readAt(channel, 4_050, data, 100))
            assertArrayEquals(content.copyOfRange(4_050, 4_150), data.copyOf(100))
        }
        assertEquals(before, folder.root.list()!!.toSet())
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
    fun finishPendingSourceDeletes_removesTheOriginalFile() {
        val store = newStore()
        val entry = store.add("/a/b.bin", content.inputStream())
        val original = folder.newFile("still-plain.bin").apply { writeBytes(content) }
        store.markSourcePendingDelete(entry, original)
        val pending = File(folder.root, entry.id + VaultStore.PENDING_SUFFIX)
        store.finishPendingSourceDeletes()
        assertFalse(original.exists())
        assertFalse(pending.exists())
        assertEquals(entry.id, store.entries().single().id)
    }

    @Test
    fun finishPendingSourceDeletes_leavesANewFileAtTheSamePath() {
        val store = newStore()
        val entry = store.add("/a/b.bin", content.inputStream())
        val original = folder.newFile("still-plain.bin").apply { writeBytes(content) }
        store.markSourcePendingDelete(entry, original)
        original.delete()
        original.writeText("new user file")
        store.finishPendingSourceDeletes()
        assertEquals("new user file", original.readText())
        assertFalse(File(folder.root, entry.id + VaultStore.PENDING_SUFFIX).exists())
        assertEquals(entry.id, store.entries().single().id)
    }

    @Test
    fun finishPendingSourceDeletes_ignoresAPathOnlyMarker() {
        val store = newStore()
        val entry = store.add("/a/b.bin", content.inputStream())
        val original = folder.newFile("still-plain.bin").apply { writeBytes(content) }
        val pending = File(folder.root, entry.id + VaultStore.PENDING_SUFFIX)
        pending.writeText(original.path)
        store.finishPendingSourceDeletes()
        assertTrue(original.exists())
        assertFalse(pending.exists())
    }

    @Test
    fun finishPendingSourceDeletes_doesNotDeleteWhenVaultCopyIsMissing() {
        val store = newStore()
        val original = folder.newFile("still-plain.bin").apply { writeBytes(content) }
        val pending = File(folder.root, "missing" + VaultStore.PENDING_SUFFIX)
        pending.writeText(VaultStore.formatPendingSource(original))
        store.finishPendingSourceDeletes()
        assertTrue(original.exists())
        assertFalse(pending.exists())
    }

    @Test
    fun add_marksOriginalForDeleteAfterTheVaultCopyExists() {
        val original = folder.newFile("plain.bin").apply { writeBytes(content) }
        val store = newStore()
        val entry = store.add(original.path, original.inputStream(), deleteOriginalWhenComplete = true)
        val pending = File(folder.root, entry.id + VaultStore.PENDING_SUFFIX)
        assertTrue(pending.exists())
        assertTrue(File(folder.root, entry.id).isFile)
        assertTrue(original.exists())
        store.finishPendingSourceDeletes()
        assertFalse(original.exists())
        assertFalse(pending.exists())
    }

    @Test
    fun finishPendingSourceDeletes_keepsTheMarkerIfTheOriginalCannotBeDeleted() {
        val store = newStore()
        val entry = store.add("/a/b.bin", content.inputStream())
        val dir = folder.newFolder("locked")
        val original = File(dir, "plain.bin").apply { writeBytes(content) }
        store.markSourcePendingDelete(entry, original)
        check(dir.setWritable(false))
        try {
            store.finishPendingSourceDeletes()
            assertTrue(original.exists())
            assertTrue(File(folder.root, entry.id + VaultStore.PENDING_SUFFIX).exists())
        } finally {
            dir.setWritable(true)
        }
    }

    @Test
    fun finishPendingSourceDeletes_leavesUserDirectoriesAlone() {
        val store = newStore()
        val entry = store.add("/a/b.bin", content.inputStream())
        val original = folder.newFolder("not-a-file")
        File(folder.root, entry.id + VaultStore.PENDING_SUFFIX).writeText(original.path)
        store.finishPendingSourceDeletes()
        assertTrue(original.isDirectory)
        assertEquals(entry.id, store.entries().single().id)
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
