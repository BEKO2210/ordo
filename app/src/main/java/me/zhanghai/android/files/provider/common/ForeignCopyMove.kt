/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java8.nio.file.AtomicMoveNotSupportedException
import java8.nio.file.CopyOption
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.Path
import java8.nio.file.StandardCopyOption
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributeView
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import java.io.IOException

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
                removeLeftoverPartFiles(target)
                // shortcut: no prompt on low-space overwrite, FileJob confirm when a dialog exists
                val usePart = !copyOptions.replaceExisting
                    || !target.exists(LinkOption.NOFOLLOW_LINKS)
                    || hasRoomForPart(target, sourceAttributes.size())
                val dest = if (usePart) partFile(target) else target
                if (usePart) {
                    try {
                        dest.deleteIfExists()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    } catch (e: UnsupportedOperationException) {
                        e.printStackTrace()
                    }
                }
                val destOpenOptions = if (usePart) {
                    arrayOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                } else {
                    arrayOf(
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING
                    )
                }
                source.newInputStream(*openOptions).use { inputStream ->
                    val outputStream = dest.newOutputStream(*destOpenOptions)
                    var successful = false
                    try {
                        inputStream.copyTo(
                            outputStream, copyOptions.progressIntervalMillis,
                            copyOptions.progressListener
                        )
                        successful = true
                    } finally {
                        try {
                            outputStream.close()
                        } finally {
                            if (!successful && usePart) {
                                try {
                                    dest.deleteIfExists()
                                } catch (e: IOException) {
                                    e.printStackTrace()
                                } catch (e: UnsupportedOperationException) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    }
                }
                if (usePart) {
                    replaceWithPart(dest, target, copyOptions.replaceExisting)
                }
            }
            sourceAttributes.isDirectory -> {
                if (copyOptions.replaceExisting) {
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

    private const val NAME_MAX = 255

    internal fun partFile(target: Path): Path {
        val name = target.fileName?.toString()
            ?: throw IOException("Cannot copy to $target without a file name")
        val longName = ".$name.ordo-part"
        if (longName.toByteArray(Charsets.UTF_8).size <= NAME_MAX) {
            return target.resolveSibling(longName)
        }
        return target.resolveSibling(".ordo-part-${Integer.toHexString(name.hashCode())}")
    }

    internal fun isPartFileName(name: String): Boolean =
        (name.startsWith(".") && name.endsWith(".ordo-part")
            && name.length > ".ordo-part".length + 1)
            || (name.startsWith(".ordo-part-") && name.length > ".ordo-part-".length)

    private fun hasRoomForPart(target: Path, sourceSize: Long): Boolean {
        if (sourceSize <= 0L) {
            return true
        }
        val directory = target.parent ?: return true
        return try {
            directory.getFileStore().usableSpace >= sourceSize
        } catch (e: IOException) {
            true
        } catch (e: UnsupportedOperationException) {
            true
        }
    }

    private fun removeLeftoverPartFiles(target: Path) {
        val directory = target.parent ?: return
        try {
            directory.newDirectoryStream().use { stream ->
                for (path in stream) {
                    val name = path.fileName?.toString() ?: continue
                    if (!isPartFileName(name)) {
                        continue
                    }
                    try {
                        path.deleteIfExists()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    } catch (e: UnsupportedOperationException) {
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: UnsupportedOperationException) {
            e.printStackTrace()
        }
    }

    private fun replaceWithPart(temp: Path, target: Path, replaceExisting: Boolean) {
        val moveOptions = buildList {
            add(StandardCopyOption.ATOMIC_MOVE)
            if (replaceExisting) {
                add(StandardCopyOption.REPLACE_EXISTING)
            }
        }.toTypedArray()
        try {
            try {
                temp.moveTo(target, *moveOptions)
                return
            } catch (e: AtomicMoveNotSupportedException) {
                // Fall through to delete-then-move on providers that cannot rename atomically.
            } catch (e: UnsupportedOperationException) {
                // Same: some foreign providers reject ATOMIC_MOVE.
            }
            if (replaceExisting) {
                target.deleteIfExists()
            }
            temp.moveTo(target)
        } catch (e: Exception) {
            try {
                temp.deleteIfExists()
            } catch (e2: IOException) {
                e.addSuppressed(e2)
            } catch (e2: UnsupportedOperationException) {
                e.addSuppressed(e2)
            }
            throw e
        }
    }
}
