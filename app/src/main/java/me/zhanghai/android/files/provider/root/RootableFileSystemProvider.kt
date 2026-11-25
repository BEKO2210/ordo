/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import java8.nio.channels.FileChannel
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.AccessMode
import java8.nio.file.CopyOption
import java8.nio.file.DirectoryStream
import java8.nio.file.FileStore
import java8.nio.file.FileSystem
import java8.nio.file.LinkOption
import java8.nio.file.OpenOption
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.FileAttributeView
import java8.nio.file.spi.FileSystemProvider
import me.zhanghai.android.files.provider.common.PathObservable
import me.zhanghai.android.files.provider.common.PathObservableProvider
import me.zhanghai.android.files.provider.common.Searchable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI

abstract class RootableFileSystemProvider(
    localProviderCreator: (FileSystemProvider) -> FileSystemProvider,
    rootProviderCreator: (FileSystemProvider) -> FileSystemProvider
) : FileSystemProvider(), PathObservableProvider, Searchable {
    protected open val localProvider: FileSystemProvider = localProviderCreator(this)
    protected open val rootProvider: FileSystemProvider = rootProviderCreator(this)

    override fun getScheme(): String = localProvider.scheme

    @Throws(IOException::class)
    override fun newFileSystem(uri: URI, env: Map<String, *>): FileSystem =
        localProvider.newFileSystem(uri, env)

    override fun getFileSystem(uri: URI): FileSystem = localProvider.getFileSystem(uri)

    override fun getPath(uri: URI): Path = localProvider.getPath(uri)

    @Throws(IOException::class)
    override fun newInputStream(path: Path, vararg options: OpenOption): InputStream =
        callRootable(path) { newInputStream(path, *options) }

    @Throws(IOException::class)
    override fun newOutputStream(path: Path, vararg options: OpenOption): OutputStream =
        callRootable(path) { newOutputStream(path, *options) }

    @Throws(IOException::class)
    override fun newFileChannel(
        path: Path,
        options: Set<OpenOption>,
        vararg attributes: FileAttribute<*>
    ): FileChannel = callRootable(path) { newFileChannel(path, options, *attributes) }

    @Throws(IOException::class)
    override fun newByteChannel(
        path: Path,
        options: Set<OpenOption>,
        vararg attributes: FileAttribute<*>
    ): SeekableByteChannel = callRootable(path) { newByteChannel(path, options, *attributes) }

    @Throws(IOException::class)
    override fun newDirectoryStream(
        directory: Path,
        filter: DirectoryStream.Filter<in Path>
    ): DirectoryStream<Path> = callRootable(directory) { newDirectoryStream(directory, filter) }

    @Throws(IOException::class)
    override fun createDirectory(directory: Path, vararg attributes: FileAttribute<*>) {
        callRootable(directory) { createDirectory(directory, *attributes) }
    }

    @Throws(IOException::class)
    override fun createSymbolicLink(link: Path, target: Path, vararg attributes: FileAttribute<*>) {
        // Only where the link is made counts: its target is a string, never touched.
        callRootable(link, noFollowLinks = true) { createSymbolicLink(link, target, *attributes) }
    }

    @Throws(IOException::class)
    override fun createLink(link: Path, existing: Path) {
        // link() makes a new name for the entry itself: neither name is followed.
        callRootable(link, existing, noFollowLinks = true, noFollowLinks2 = true) { createLink(link, existing) }
    }

    @Throws(IOException::class)
    override fun delete(path: Path) {
        callRootable(path, noFollowLinks = true) { delete(path) }
    }

    @Throws(IOException::class)
    override fun readSymbolicLink(link: Path): Path = callRootable(link, noFollowLinks = true) { readSymbolicLink(link) }

    @Throws(IOException::class)
    override fun copy(source: Path, target: Path, vararg options: CopyOption) {
        // The target entry is replaced, never followed.
        callRootable(source, target, LinkOption.NOFOLLOW_LINKS in options, true) {
            copy(source, target, *options)
        }
    }

    @Throws(IOException::class)
    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        // rename() moves the entry itself: neither name is followed.
        callRootable(source, target, noFollowLinks = true, noFollowLinks2 = true) { move(source, target, *options) }
    }

    @Throws(IOException::class)
    override fun isSameFile(path: Path, path2: Path): Boolean =
        callRootable(path, path2, true, true, isAttributeAccess = true) { isSameFile(path, path2) }

    @Throws(IOException::class)
    override fun isHidden(path: Path): Boolean = callRootable(path, true) { isHidden(path) }

    @Throws(IOException::class)
    override fun getFileStore(path: Path): FileStore = localProvider.getFileStore(path)

    @Throws(IOException::class)
    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        callRootable(path) {checkAccess(path, *modes) }
    }

    override fun <V : FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption
    ): V? = localProvider.getFileAttributeView(path, type, *options)

    @Throws(IOException::class)
    override fun <A : BasicFileAttributes> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption
    ): A = callRootable(path, true, LinkOption.NOFOLLOW_LINKS in options) {
        readAttributes(path, type, *options)
    }

    @Throws(IOException::class)
    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption
    ): Map<String, Any> = callRootable(path, true, LinkOption.NOFOLLOW_LINKS in options) {
        readAttributes(path, attributes, *options)
    }

    @Throws(IOException::class)
    override fun setAttribute(
        path: Path,
        attribute: String,
        value: Any,
        vararg options: LinkOption
    ) {
        callRootable(path, true, LinkOption.NOFOLLOW_LINKS in options) {
            setAttribute(path, attribute, value, *options)
        }
    }

    @Throws(IOException::class)
    override fun observe(path: Path, intervalMillis: Long): PathObservable {
        if (localProvider !is PathObservableProvider) {
            throw UnsupportedOperationException()
        }
        return callRootable(path) {
            // observe() may or may not be able to detect denied access, and that is expensive on
            // Linux (having to create the WatchService first before registering a WatchKey). So we
            // check the access beforehand.
            if (this == localProvider) {
                val attributes = try {
                    readAttributes(path, BasicFileAttributes::class.java)
                } catch (ignored: IOException) {
                    readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                }
                if (attributes.isSymbolicLink) {
                    readSymbolicLink(path)
                } else {
                    checkAccess(path, AccessMode.READ)
                }
            }
            (this as PathObservableProvider).observe(path, intervalMillis)
        }
    }

    @Throws(IOException::class)
    override fun search(
        directory: Path,
        query: String,
        intervalMillis: Long,
        listener: (List<Path>) -> Unit
    ) {
        callRootable(directory) {
            (this as Searchable).search(directory, query, intervalMillis, listener)
        }
    }

    @Throws(IOException::class)
    private fun <R> callRootable(
        path: Path,
        isAttributeAccess: Boolean = false,
        noFollowLinks: Boolean = false,
        block: FileSystemProvider.() -> R
    ): R = callRootable(path, isAttributeAccess, noFollowLinks, localProvider, rootProvider, block)

    @Throws(IOException::class)
    private fun <R> callRootable(
        path1: Path,
        path2: Path,
        noFollowLinks: Boolean = false,
        noFollowLinks2: Boolean = false,
        isAttributeAccess: Boolean = false,
        block: FileSystemProvider.() -> R
    ): R = callRootable(
        path1, path2, isAttributeAccess, noFollowLinks, noFollowLinks2, localProvider, rootProvider, block
    )
}
