/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java8.nio.file.AtomicMoveNotSupportedException
import java8.nio.file.CopyOption
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.Path
import java8.nio.file.StandardCopyOption
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributeView
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import java.io.IOException
import java.util.concurrent.ThreadLocalRandom

internal object ForeignCopyMove {
    @Throws(IOException::class)
    fun copy(source: Path, target: Path, vararg options: CopyOption) {
        val copyOptions = options.toCopyOptions()
        if (copyOptions.atomicMove) {
            throw UnsupportedOperationException(StandardCopyOption.ATOMIC_MOVE.toString())
        }
        val linkOptions = if (copyOptions.noFollowLinks) {
            arrayOf(LinkOption.NOFOLLOW_LINKS)
        } else {
            emptyArray()
        }
        val sourceAttributes = source.readAttributes(BasicFileAttributes::class.java, *linkOptions)
        if (!(sourceAttributes.isRegularFile || sourceAttributes.isDirectory
                || sourceAttributes.isSymbolicLink)) {
            throw IOException("Cannot copy special file to foreign provider")
        }
        if (!copyOptions.replaceExisting && target.exists(LinkOption.NOFOLLOW_LINKS)) {
            throw FileAlreadyExistsException(source.toString(), target.toString(), null)
        }
        when {
            sourceAttributes.isRegularFile -> {
                val openOptions = if (copyOptions.noFollowLinks) {
                    arrayOf(LinkOption.NOFOLLOW_LINKS)
                } else {
                    emptyArray()
                }
                if (target.exists(LinkOption.NOFOLLOW_LINKS)) {
                    val targetAttributes = target.readAttributes(
                        BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
                    )
                    if (targetAttributes.isDirectory) {
                        throw FileSystemException(
                            source.toString(), target.toString(),
                            "Cannot replace a directory with a file"
                        )
                    }
                    if (!copyOptions.replaceExisting) {
                        throw FileAlreadyExistsException(
                            source.toString(), target.toString(), null
                        )
                    }
                }
                copyRegularFileToPart(source, target, openOptions, copyOptions)
            }
            sourceAttributes.isDirectory -> {
                if (copyOptions.replaceExisting && target.exists(LinkOption.NOFOLLOW_LINKS)) {
                    val targetAttributes = target.readAttributes(
                        BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
                    )
                    if (!targetAttributes.isDirectory) {
                        throw FileSystemException(
                            source.toString(), target.toString(),
                            "Cannot replace a directory with a file"
                        )
                    }
                    target.deleteIfExists()
                }
                target.createDirectory()
                copyOptions.progressListener?.invoke(sourceAttributes.size())
            }
            sourceAttributes.isSymbolicLink -> {
                val sourceTarget = source.readSymbolicLink()
                try {
                    // Might throw UnsupportedOperationException, so we cannot delete beforehand.
                    target.createSymbolicLink(sourceTarget)
                } catch (e: FileAlreadyExistsException) {
                    if (!copyOptions.replaceExisting) {
                        throw e
                    }
                    target.deleteIfExists()
                    target.createSymbolicLink(sourceTarget)
                }
                copyOptions.progressListener?.invoke(sourceAttributes.size())
            }
            else -> throw AssertionError()
        }
        // We don't take error when copying attribute fatal, so errors will only be logged from
        // now on.
        val targetAttributeView = target.getFileAttributeView(BasicFileAttributeView::class.java)!!
        val lastModifiedTime = sourceAttributes.lastModifiedTime()
            .takeIf { it != FileTime::class.EPOCH }
        val lastAccessTime = if (copyOptions.copyAttributes) {
            sourceAttributes.lastAccessTime().takeIf { it != FileTime::class.EPOCH }
        } else {
            null
        }
        val creationTime = if (copyOptions.copyAttributes) {
            sourceAttributes.creationTime().takeIf { it != FileTime::class.EPOCH }
        } else {
            null
        }
        try {
            targetAttributeView.setTimes(lastModifiedTime, lastAccessTime, creationTime)
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: UnsupportedOperationException) {
            e.printStackTrace()
        }
    }

    @Throws(IOException::class)
    fun move(source: Path, target: Path, vararg options: CopyOption) {
        val copyOptions = options.toCopyOptions()
        if (copyOptions.atomicMove) {
            throw AtomicMoveNotSupportedException(
                source.toString(), target.toString(),
                "Cannot move file atomically to foreign provider"
            )
        }
        val optionsForCopy = if (copyOptions.copyAttributes && copyOptions.noFollowLinks) {
            options
        } else {
            CopyOptions(
                copyOptions.replaceExisting, true, false, true, copyOptions.progressIntervalMillis,
                copyOptions.progressListener
            ).toArray()
        }
        copy(source, target, *optionsForCopy)
        try {
            source.delete()
        } catch (e: IOException) {
            if (e !is NoSuchFileException) {
                try {
                    target.delete()
                } catch (e2: IOException) {
                    e.addSuppressed(e2)
                } catch (e2: UnsupportedOperationException) {
                    e.addSuppressed(e2)
                }
            }
            throw e
        } catch (e: UnsupportedOperationException) {
            try {
                target.delete()
            } catch (e2: IOException) {
                e.addSuppressed(e2)
            } catch (e2: UnsupportedOperationException) {
                e.addSuppressed(e2)
            }
            throw e
        }
    }

    @Throws(IOException::class)
    private fun copyRegularFileToPart(
        source: Path,
        target: Path,
        openOptions: Array<LinkOption>,
        copyOptions: CopyOptions
    ) {
        var lastExists: FileAlreadyExistsException? = null
        repeat(8) { attempt ->
            val dest = if (attempt == 0) partFile(target) else uniquePartFile(target)
            var created = false
            var copied = false
            try {
                source.newInputStream(*openOptions).use { inputStream ->
                    val outputStream = dest.newOutputStream(
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
                    )
                    created = true
                    try {
                        inputStream.copyTo(
                            outputStream, copyOptions.progressIntervalMillis,
                            copyOptions.progressListener
                        )
                        copied = true
                    } finally {
                        outputStream.close()
                    }
                }
                replaceWithPart(dest, target, copyOptions.replaceExisting)
                return
            } catch (e: FileAlreadyExistsException) {
                if (!created) {
                    lastExists = e
                    return@repeat
                }
                throw e
            } finally {
                if (created && !copied) {
                    deleteQuietly(dest)
                }
            }
        }
        throw lastExists ?: IOException("Cannot create a temporary copy next to $target")
    }

    private const val NAME_MAX = 255

    internal fun partFile(target: Path): Path = sidecarFile(target, "part", null)

    internal fun uniquePartFile(target: Path): Path =
        sidecarFile(target, "part", uniqueSidecarId())

    internal fun isPartFileName(name: String): Boolean =
        (name.startsWith(".") && name.endsWith(".ordo-part")
            && name.length > ".ordo-part".length + 1)
            || (name.startsWith(".ordo-part-") && name.length > ".ordo-part-".length)

    internal fun backupFile(target: Path): Path = sidecarFile(target, "bak", null)

    internal fun uniqueBackupFile(target: Path): Path =
        sidecarFile(target, "bak", uniqueSidecarId())

    private fun uniqueSidecarId(): String =
        Integer.toHexString(ThreadLocalRandom.current().nextInt())

    private fun sidecarFile(target: Path, kind: String, id: String?): Path {
        val name = target.fileName?.toString()
            ?: throw IOException("Cannot copy to $target without a file name")
        val suffix = if (id != null) ".ordo-$kind-$id" else ".ordo-$kind"
        val longName = ".$name$suffix"
        if (longName.toByteArray(Charsets.UTF_8).size <= NAME_MAX) {
            return target.resolveSibling(longName)
        }
        return target.resolveSibling(".ordo-$kind-${id ?: Integer.toHexString(name.hashCode())}")
    }

    private fun replaceWithPart(temp: Path, target: Path, replaceExisting: Boolean) {
        val targetExisted = try {
            target.exists(LinkOption.NOFOLLOW_LINKS)
        } catch (e: IOException) {
            true
        } catch (e: UnsupportedOperationException) {
            true
        }
        PartReplace.replace(
            temp, target, replaceExisting, targetExisted,
            backups = sequence {
                yield(backupFile(target))
                while (true) {
                    yield(uniqueBackupFile(target))
                }
            },
            move = { source, destination -> moveWithoutReplace(source, destination) },
            delete = { path -> deleteQuietly(path) }
        )
    }

    private fun moveWithoutReplace(source: Path, target: Path) {
        try {
            source.moveTo(target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            source.moveTo(target)
        } catch (e: UnsupportedOperationException) {
            source.moveTo(target)
        }
    }

    private fun deleteQuietly(path: Path, cause: Exception? = null) {
        try {
            path.deleteIfExists()
        } catch (e: IOException) {
            cause?.addSuppressed(e) ?: e.printStackTrace()
        } catch (e: UnsupportedOperationException) {
            cause?.addSuppressed(e) ?: e.printStackTrace()
        }
    }
}
