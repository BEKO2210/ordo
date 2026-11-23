/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ftpserver

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.nio.ByteBuffer
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.AccessDeniedException
import java8.nio.file.AccessMode
import java8.nio.file.CopyOption
import java8.nio.file.DirectoryStream
import java8.nio.file.FileStore
import java8.nio.file.FileSystem
import java8.nio.file.LinkOption
import java8.nio.file.OpenOption
import java8.nio.file.Path
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.FileAttributeView
import java8.nio.file.attribute.FileTime
import java8.nio.file.attribute.UserPrincipalLookupService
import java8.nio.file.spi.FileSystemProvider
import java8.nio.file.WatchService
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.ByteStringListPath
import me.zhanghai.android.files.provider.common.PathListDirectoryStream
import me.zhanghai.android.files.provider.common.toByteString
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
        private lateinit var fileSystem: JvmFileSystem

        @JvmStatic
        @BeforeClass
        fun setUpFileSystemProvider() {
            // AudioQueueTest/ForeignCopyMoveTest normally install LinuxFileSystemProvider first in
            // the shared test JVM. It reaches Android's Process/root strategy and native syscalls,
            // so it cannot be the backing provider for a standalone plain-JVM test.
            fileSystem = JvmFileSystemProvider().fileSystem
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
        val root = fileSystem.getPath(folder.root.path)
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
        val root = fileSystem.getPath(folder.root.path)
        val existing = folder.newFile("read-only.txt")
        val file = file(fileSystem.getPath(existing.path), root, writable = false)

        assertTrue(file.doesExist())
        assertTrue(file.isReadable)
        assertFalse(file.isWritable)
        assertFalse(file.isRemovable)
        assertFalse(file.delete())
        assertTrue(existing.exists())
    }

    /** A small java.io-backed provider: this test is about ProviderFtpFile, not Android syscalls. */
    private class JvmFileSystemProvider : FileSystemProvider() {
        val fileSystem = JvmFileSystem(this)

        override fun getScheme(): String = "file"

        override fun newFileSystem(uri: URI, env: Map<String, *>): FileSystem = fileSystem

        override fun getFileSystem(uri: URI): FileSystem = fileSystem

        override fun getPath(uri: URI): Path = fileSystem.getPath(File(uri).path)

        override fun newByteChannel(
            file: Path,
            options: Set<OpenOption>,
            vararg attributes: FileAttribute<*>
        ): SeekableByteChannel {
            val path = file.asFile()
            if (StandardOpenOption.CREATE_NEW in options && path.exists()) {
                throw java8.nio.file.FileAlreadyExistsException(path.path)
            }
            if (StandardOpenOption.CREATE in options || StandardOpenOption.CREATE_NEW in options) {
                path.parentFile?.mkdirs()
                path.createNewFile()
            }
            val writable = StandardOpenOption.WRITE in options || StandardOpenOption.APPEND in options
            val channel = RandomAccessFile(path, if (writable) "rw" else "r")
            if (StandardOpenOption.TRUNCATE_EXISTING in options) channel.setLength(0)
            if (StandardOpenOption.APPEND in options) channel.seek(channel.length())
            return RandomAccessFileChannel(channel)
        }

        override fun newDirectoryStream(
            directory: Path,
            filter: DirectoryStream.Filter<in Path>
        ): DirectoryStream<Path> {
            val children = directory.asFile().listFiles() ?: throw IOException(directory.toString())
            return PathListDirectoryStream(
                children.map { fileSystem.getPath(it.name) }, filter
            )
        }

        override fun createDirectory(directory: Path, vararg attributes: FileAttribute<*>) {
            if (!directory.asFile().mkdir()) throw IOException(directory.toString())
        }

        override fun delete(path: Path) {
            if (!path.asFile().delete()) throw IOException(path.toString())
        }

        override fun copy(source: Path, target: Path, vararg options: CopyOption) {
            source.asFile().copyTo(target.asFile())
        }

        override fun move(source: Path, target: Path, vararg options: CopyOption) {
            if (!source.asFile().renameTo(target.asFile())) throw IOException(source.toString())
        }

        override fun isSameFile(path: Path, path2: Path): Boolean =
            path.asFile().canonicalFile == path2.asFile().canonicalFile

        override fun isHidden(path: Path): Boolean = path.asFile().isHidden

        override fun getFileStore(path: Path): FileStore = throw UnsupportedOperationException()

        override fun checkAccess(path: Path, vararg modes: AccessMode) {
            val file = path.asFile()
            val allowed = if (modes.isEmpty()) {
                file.exists()
            } else {
                modes.all {
                    when (it) {
                        AccessMode.READ -> file.canRead()
                        AccessMode.WRITE -> file.canWrite()
                        AccessMode.EXECUTE -> file.canExecute()
                    }
                }
            }
            if (!allowed) throw AccessDeniedException(path.toString())
        }

        override fun <V : FileAttributeView> getFileAttributeView(
            path: Path,
            type: Class<V>,
            vararg options: LinkOption
        ): V? = null

        override fun <A : BasicFileAttributes> readAttributes(
            path: Path,
            type: Class<A>,
            vararg options: LinkOption
        ): A {
            if (type != BasicFileAttributes::class.java) throw UnsupportedOperationException()
            @Suppress("UNCHECKED_CAST")
            return JvmBasicFileAttributes(path.asFile()) as A
        }

        override fun readAttributes(
            path: Path,
            attributes: String,
            vararg options: LinkOption
        ): Map<String, Any> = throw UnsupportedOperationException()

        override fun setAttribute(
            path: Path,
            attribute: String,
            value: Any,
            vararg options: LinkOption
        ) {
            if (attribute != "basic:lastModifiedTime") throw UnsupportedOperationException(attribute)
            path.asFile().setLastModified((value as FileTime).toMillis())
        }

        private fun Path.asFile(): File = (this as JvmPath).toFile()
    }

    private class JvmFileSystem(private val fileSystemProvider: JvmFileSystemProvider) : FileSystem() {
        val rootDirectory = JvmPath(this, File.separator.toByteString())

        override fun provider(): FileSystemProvider = fileSystemProvider
        override fun close() = Unit
        override fun isOpen(): Boolean = true
        override fun isReadOnly(): Boolean = false
        override fun getSeparator(): String = File.separator
        override fun getRootDirectories(): Iterable<Path> = listOf(rootDirectory)
        override fun getFileStores(): Iterable<FileStore> = emptyList()
        override fun supportedFileAttributeViews(): Set<String> = setOf("basic")
        override fun getPath(first: String, vararg more: String): JvmPath =
            JvmPath(this, (listOf(first) + more).joinToString(File.separator).toByteString())
        override fun getPathMatcher(syntaxAndPattern: String) = throw UnsupportedOperationException()
        override fun getUserPrincipalLookupService(): UserPrincipalLookupService =
            throw UnsupportedOperationException()
        override fun newWatchService(): WatchService = throw UnsupportedOperationException()
    }

    private class JvmPath : ByteStringListPath<JvmPath> {
        private val fileSystem: JvmFileSystem

        constructor(fileSystem: JvmFileSystem, path: ByteString) : super(File.separatorChar.code.toByte(), path) {
            this.fileSystem = fileSystem
        }

        private constructor(
            fileSystem: JvmFileSystem,
            absolute: Boolean,
            segments: List<ByteString>
        ) : super(File.separatorChar.code.toByte(), absolute, segments) {
            this.fileSystem = fileSystem
        }

        override fun isPathAbsolute(path: ByteString): Boolean =
            path.isNotEmpty() && path[0] == File.separatorChar.code.toByte()
        override fun createPath(path: ByteString) = JvmPath(fileSystem, path)
        override fun createPath(absolute: Boolean, segments: List<ByteString>) =
            JvmPath(fileSystem, absolute, segments)
        override val defaultDirectory: JvmPath
            get() = JvmPath(fileSystem, File("").absolutePath.toByteString())
        override fun getFileSystem(): JvmFileSystem = fileSystem
        override fun getRoot(): JvmPath? = if (isAbsolute) fileSystem.rootDirectory else null
        override fun toRealPath(vararg options: LinkOption): JvmPath =
            JvmPath(fileSystem, toFile().canonicalPath.toByteString())
        override fun toFile(): File = File(toString())
        override fun register(
            watcher: WatchService,
            events: Array<java8.nio.file.WatchEvent.Kind<*>>,
            vararg modifiers: java8.nio.file.WatchEvent.Modifier
        ) = throw UnsupportedOperationException()
    }

    private class RandomAccessFileChannel(private val file: RandomAccessFile) : SeekableByteChannel {
        private var open = true
        override fun read(dst: ByteBuffer): Int = file.channel.read(dst)
        override fun write(src: ByteBuffer): Int = file.channel.write(src)
        override fun position(): Long = file.filePointer
        override fun position(newPosition: Long): SeekableByteChannel = apply { file.seek(newPosition) }
        override fun size(): Long = file.length()
        override fun truncate(size: Long): SeekableByteChannel = apply { file.setLength(size) }
        override fun isOpen(): Boolean = open
        override fun close() { open = false; file.close() }
    }

    private class JvmBasicFileAttributes(private val file: File) : BasicFileAttributes {
        private val modified = FileTime.fromMillis(file.lastModified())
        override fun lastModifiedTime(): FileTime = modified
        override fun lastAccessTime(): FileTime = modified
        override fun creationTime(): FileTime = modified
        override fun isRegularFile(): Boolean = file.isFile
        override fun isDirectory(): Boolean = file.isDirectory
        override fun isSymbolicLink(): Boolean = false
        override fun isOther(): Boolean = !file.isFile && !file.isDirectory
        override fun size(): Long = file.length()
        override fun fileKey(): Any? = null
    }
}
