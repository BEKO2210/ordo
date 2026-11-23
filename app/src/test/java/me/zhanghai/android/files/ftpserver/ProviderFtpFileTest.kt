/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ftpserver

import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.spi.FileSystemProvider
import me.zhanghai.android.files.provider.linux.LinuxFileSystemProvider
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderFtpFileTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installFileSystemProvider() {
            runCatching { FileSystemProvider.installDefaultProvider(LinuxFileSystemProvider) }
        }
    }

    @get:Rule
    val folder = TemporaryFolder()

    private fun file(path: Path, root: Path, writable: Boolean = true): ProviderFtpFile {
        val user = BaseUser().apply {
            name = "test"
            homeDirectory = root.toUri().toString()
            authorities = if (writable) listOf(WritePermission()) else emptyList()
        }
        return ProviderFtpFile(path, root.relativize(path), user)
    }

    @Test
    fun writableUserCanCreateTransferListMoveAndDelete() {
        val root = Paths.get(folder.root.toURI())
        val directoryPath = root.resolve("transfer")
        val directory = file(directoryPath, root)
        assertEquals("transfer", directory.name)
        assertEquals("/transfer", directory.absolutePath)
        assertFalse(directory.doesExist())
        assertTrue(directory.mkdir())
        assertTrue(directory.isDirectory)

        val source = file(directoryPath.resolve("source.bin"), root)
        val content = byteArrayOf(1, 2, 3, 4)
        source.createOutputStream(0).use { it.write(content) }
        assertTrue(source.isFile)
        assertEquals(content.size.toLong(), source.size)
        assertArrayEquals(content, source.createInputStream(0).use { it.readBytes() })
        assertEquals(listOf("source.bin"), directory.listFiles()!!.map { it.name })

        val target = file(directoryPath.resolve("target.bin"), root)
        assertTrue(source.move(target))
        assertFalse(source.doesExist())
        assertTrue(target.doesExist())
        assertTrue(target.delete())
        assertTrue(directory.delete())
    }

    @Test
    fun readOnlyUserCannotMutate() {
        val root = Paths.get(folder.root.toURI())
        val existing = folder.newFile("read-only.txt")
        val file = file(Paths.get(existing.toURI()), root, writable = false)

        assertTrue(file.doesExist())
        assertTrue(file.isReadable)
        assertFalse(file.isWritable)
        assertFalse(file.isRemovable)
        assertFalse(file.delete())
        assertTrue(existing.exists())
    }
}
