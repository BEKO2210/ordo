/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.linux

import android.system.OsConstants
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.StandardCopyOption
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.CopyOptions
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.linux.syscall.Constants
import me.zhanghai.android.files.provider.linux.syscall.StructTimespec
import me.zhanghai.android.files.provider.linux.syscall.Syscall
import me.zhanghai.android.files.provider.linux.syscall.SyscallException
import java.io.IOException
import java.io.InterruptedIOException

internal object LinuxCopyMove {
    private const val SEND_FILE_COUNT = 8 * 1024

    private val XATTR_NAME_PREFIX_USER = "user.".toByteString()

    private val PART_SUFFIX = ".ordo-part".toByteString()
    private val DOT = ".".toByteString()
    private val SLASH = '/'.code.toByte()

    @Throws(IOException::class)
    fun copy(source: ByteString, target: ByteString, copyOptions: CopyOptions) {
        if (copyOptions.atomicMove) {
            throw UnsupportedOperationException(StandardCopyOption.ATOMIC_MOVE.toString())
        }
        val sourceStat = try {
            if (copyOptions.noFollowLinks) Syscall.lstat(source) else Syscall.stat(source)
        } catch (e: SyscallException) {
            throw e.toFileSystemException(source.toString())
        }
        val targetStat = try {
            Syscall.lstat(target)
        } catch (e: SyscallException) {
            if (e.errno != OsConstants.ENOENT) {
                throw e.toFileSystemException(target.toString())
            }
            // Ignored.
            null
        }
        if (targetStat != null) {
            if (sourceStat.st_dev == targetStat.st_dev && sourceStat.st_ino == targetStat.st_ino) {
                copyOptions.progressListener?.invoke(sourceStat.st_size)
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
            // Symbolic links may not be supported so we cannot simply remove the target here.
        }
        if (OsConstants.S_ISREG(sourceStat.st_mode)) {
            val sourceFd = try {
                Syscall.open(source, OsConstants.O_RDONLY, 0)
            } catch (e: SyscallException) {
                throw e.toFileSystemException(source.toString())
            }
            val temp = partFile(target)
            try {
                removeIfExists(temp)
                val tempFd = try {
                    Syscall.open(
                        temp,
                        OsConstants.O_WRONLY or OsConstants.O_TRUNC or OsConstants.O_CREAT
                            or OsConstants.O_EXCL,
                        sourceStat.st_mode
                    )
                } catch (e: SyscallException) {
                    e.maybeThrowInvalidFileNameException(temp.toString())
                    throw e.toFileSystemException(temp.toString())
                }
                var successful = false
                try {
                    val progressIntervalMillis = copyOptions.progressIntervalMillis
                    val progressListener = copyOptions.progressListener
                    var lastProgressMillis = System.currentTimeMillis()
                    var copiedSize = 0L
                    while (true) {
                        val sentSize = try {
                            Syscall.sendfile(tempFd, sourceFd, null, SEND_FILE_COUNT.toLong())
                        } catch (e: SyscallException) {
                            throw e.toFileSystemException(source.toString(), target.toString())
                        }
                        if (sentSize == 0L) {
                            break
                        }
                        copiedSize += sentSize
                        throwIfInterrupted()
                        val currentTimeMillis = System.currentTimeMillis()
                        if (progressListener != null
                            && currentTimeMillis >= lastProgressMillis + progressIntervalMillis) {
                            progressListener(copiedSize)
                            lastProgressMillis = currentTimeMillis
                            copiedSize = 0
                        }
                    }
                    progressListener?.invoke(copiedSize)
                    successful = true
                } finally {
                    try {
                        Syscall.close(tempFd)
                    } catch (e: SyscallException) {
                        throw e.toFileSystemException(temp.toString())
                    } finally {
                        if (!successful) {
                            try {
                                Syscall.remove(temp)
                            } catch (e: SyscallException) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
                renamePartOverTarget(temp, target, copyOptions.replaceExisting)
            } finally {
                try {
                    Syscall.close(sourceFd)
                } catch (e: SyscallException) {
                    throw e.toFileSystemException(source.toString())
                }
            }
        } else if (OsConstants.S_ISDIR(sourceStat.st_mode)) {
            if (targetStat != null) {
                try {
                    Syscall.remove(target)
                } catch (e: SyscallException) {
                    if (e.errno != OsConstants.ENOENT) {
                        throw e.toFileSystemException(target.toString())
                    }
                }
            }
            try {
                Syscall.mkdir(target, sourceStat.st_mode)
            } catch (e: SyscallException) {
                e.maybeThrowInvalidFileNameException(target.toString())
                throw e.toFileSystemException(target.toString())
            }
            copyOptions.progressListener?.invoke(sourceStat.st_size)
        } else if (OsConstants.S_ISLNK(sourceStat.st_mode)) {
            val sourceTarget = try {
                Syscall.readlink(source)
            } catch (e: SyscallException) {
                throw e.toFileSystemException(source.toString())
            }
            try {
                Syscall.symlink(sourceTarget, target)
            } catch (e: SyscallException) {
                if (e.errno == OsConstants.EEXIST && copyOptions.replaceExisting) {
                    try {
                        Syscall.remove(target)
                    } catch (e2: SyscallException) {
                        if (e2.errno != OsConstants.ENOENT) {
                            e2.addSuppressed(e.toFileSystemException(target.toString()))
                            throw e2.toFileSystemException(target.toString())
                        }
                    }
                    try {
                        Syscall.symlink(sourceTarget, target)
                    } catch (e2: SyscallException) {
                        e2.addSuppressed(e.toFileSystemException(target.toString()))
                        throw e2.toFileSystemException(target.toString())
                    }
                }
                e.maybeThrowInvalidFileNameException(target.toString())
                throw e.toFileSystemException(target.toString())
            }
            copyOptions.progressListener?.invoke(sourceStat.st_size)
        } else {
            throw FileSystemException(source.toString(), null, "st_mode ${sourceStat.st_mode}")
        }
        // We don't take error when copying attribute fatal, so errors will only be logged from now
        // on.
        // Ownership should be copied before permissions so that special permission bits like
        // setuid work properly.
        try {
            if (copyOptions.copyAttributes) {
                Syscall.lchown(target, sourceStat.st_uid, sourceStat.st_gid)
            }
        } catch (e: SyscallException) {
            e.printStackTrace()
        }
        try {
            if (!OsConstants.S_ISLNK(sourceStat.st_mode)) {
                Syscall.chmod(target, sourceStat.st_mode)
            }
        } catch (e: SyscallException) {
            e.printStackTrace()
        }
        // TODO: Change modified time last?
        try {
            val times = arrayOf(
                if (copyOptions.copyAttributes) {
                    sourceStat.st_atim
                } else {
                    StructTimespec(0, Constants.UTIME_OMIT)
                }, sourceStat.st_mtim
            )
            Syscall.lutimens(target, times)
        } catch (e: SyscallException) {
            e.printStackTrace()
        }
        try {
            // TODO: Allow u+rw temporarily if we are to copy xattrs.
            val xattrNames = Syscall.llistxattr(source)
            for (xattrName in xattrNames) {
                if (!(copyOptions.copyAttributes || xattrName.startsWith(XATTR_NAME_PREFIX_USER))) {
                    continue
                }
                val xattrValue = Syscall.lgetxattr(target, xattrName)
                Syscall.lsetxattr(target, xattrName, xattrValue, 0)
            }
        } catch (e: SyscallException) {
            e.printStackTrace()
        }
    }

    @Throws(InterruptedIOException::class)
    private fun throwIfInterrupted() {
        if (Thread.interrupted()) {
            throw InterruptedIOException()
        }
    }

    @Throws(IOException::class)
    fun move(source: ByteString, target: ByteString, copyOptions: CopyOptions) {
        val sourceStat = try {
            Syscall.lstat(source)
        } catch (e: SyscallException) {
            throw e.toFileSystemException(source.toString())
        }
        val targetStat = try {
            Syscall.lstat(target)
        } catch (e: SyscallException) {
            if (e.errno != OsConstants.ENOENT) {
                throw e.toFileSystemException(target.toString())
            }
            // Ignored.
            null
        }
        if (targetStat != null) {
            if (sourceStat.st_dev == targetStat.st_dev && sourceStat.st_ino == targetStat.st_ino) {
                if (sameInodeStillNeedsRename(source, target)) {
                    try {
                        Syscall.rename(source, target)
                    } catch (e: SyscallException) {
                        throw e.toFileSystemException(source.toString(), target.toString())
                    }
                }
                copyOptions.progressListener?.invoke(sourceStat.st_size)
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
            // A file is replaced by rename itself. Empty directories still have to go first.
            if (OsConstants.S_ISDIR(targetStat.st_mode)) {
                try {
                    Syscall.remove(target)
                } catch (e: SyscallException) {
                    throw e.toFileSystemException(target.toString())
                }
            }
        }
        var renameSuccessful = false
        try {
            Syscall.rename(source, target)
            renameSuccessful = true
        } catch (e: SyscallException) {
            if (copyOptions.atomicMove) {
                e.maybeThrowAtomicMoveNotSupportedException(source.toString(), target.toString())
                e.maybeThrowInvalidFileNameException(target.toString())
                throw e.toFileSystemException(source.toString(), target.toString())
            }
            // Ignored.
        }
        if (renameSuccessful) {
            copyOptions.progressListener?.invoke(sourceStat.st_size)
            return
        }
        if (copyOptions.atomicMove) {
            throw AssertionError()
        }
        var copyOptions = copyOptions
        if (!copyOptions.copyAttributes || !copyOptions.noFollowLinks) {
            copyOptions = CopyOptions(
                copyOptions.replaceExisting, true, false, true, copyOptions.progressIntervalMillis,
                copyOptions.progressListener
            )
        }
        copy(source, target, copyOptions)
        try {
            Syscall.remove(source)
        } catch (e: SyscallException) {
            if (e.errno != OsConstants.ENOENT) {
                try {
                    Syscall.remove(target)
                } catch (e2: SyscallException) {
                    e.addSuppressed(e2.toFileSystemException(target.toString()))
                }
            }
            throw e.toFileSystemException(source.toString())
        }
    }

    internal fun partFile(target: ByteString): ByteString {
        val index = target.lastIndexOf(SLASH)
        val directory = if (index >= 0) target.substring(0, index + 1) else ByteString.EMPTY
        val name = if (index >= 0) target.substring(index + 1) else target
        return directory + DOT + name + PART_SUFFIX
    }

    internal fun sameInodeStillNeedsRename(source: ByteString, target: ByteString): Boolean =
        source != target

    private fun removeIfExists(path: ByteString) {
        try {
            Syscall.remove(path)
        } catch (e: SyscallException) {
            if (e.errno != OsConstants.ENOENT) {
                throw e.toFileSystemException(path.toString())
            }
        }
    }

    private fun renamePartOverTarget(
        temp: ByteString,
        target: ByteString,
        replaceExisting: Boolean
    ) {
        try {
            Syscall.rename(temp, target)
            return
        } catch (e: SyscallException) {
            if (!replaceExisting) {
                try {
                    Syscall.remove(temp)
                } catch (e2: SyscallException) {
                    e.addSuppressed(e2.toFileSystemException(temp.toString()))
                }
                e.maybeThrowInvalidFileNameException(target.toString())
                throw e.toFileSystemException(temp.toString(), target.toString())
            }
        }
        try {
            Syscall.remove(target)
        } catch (e: SyscallException) {
            if (e.errno != OsConstants.ENOENT) {
                try {
                    Syscall.remove(temp)
                } catch (e2: SyscallException) {
                    e.addSuppressed(e2.toFileSystemException(temp.toString()))
                }
                throw e.toFileSystemException(target.toString())
            }
        }
        try {
            Syscall.rename(temp, target)
        } catch (e: SyscallException) {
            try {
                Syscall.remove(temp)
            } catch (e2: SyscallException) {
                e.addSuppressed(e2.toFileSystemException(temp.toString()))
            }
            e.maybeThrowInvalidFileNameException(target.toString())
            throw e.toFileSystemException(temp.toString(), target.toString())
        }
    }
}
