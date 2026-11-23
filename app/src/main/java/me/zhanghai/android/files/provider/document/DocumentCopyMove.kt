/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.document

import android.net.Uri
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.StandardCopyOption
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.provider.common.CopyOptions
import me.zhanghai.android.files.provider.content.resolver.ResolverException
import me.zhanghai.android.files.provider.document.resolver.DocumentResolver
import java.io.IOException
import java.io.InterruptedIOException

internal object DocumentCopyMove {
    @Throws(IOException::class)
    fun copy(source: DocumentPath, target: DocumentPath, copyOptions: CopyOptions): Uri {
        if (copyOptions.atomicMove) {
            throw UnsupportedOperationException(StandardCopyOption.ATOMIC_MOVE.toString())
        }
        if (source == target) {
            val targetUri = try {
                DocumentResolver.getDocumentUri(target)
            } catch (e: ResolverException) {
                throw e.toFileSystemException(target.toString())
            }
            copyOptions.progressListener?.invokeWithSize(targetUri)
            return targetUri
        }
        val targetExists = DocumentResolver.exists(target)
        if (targetExists) {
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(target.toString())
            }
            refuseFileFolderReplace(source, target)
            try {
                DocumentResolver.remove(target)
            } catch (e: ResolverException) {
                throw e.toFileSystemException(target.toString())
            }
        }
        return try {
            DocumentResolver.copy(
                source, target, copyOptions.progressIntervalMillis, copyOptions.progressListener
            )
        } catch (e: ResolverException) {
            (e.cause as? InterruptedIOException)?.let { throw it }
            throw e.toFileSystemException(source.toString(), target.toString())
        }
    }

    @Throws(IOException::class)
    fun move(source: DocumentPath, target: DocumentPath, copyOptions: CopyOptions): Uri {
        if (source == target) {
            val targetUri = try {
                DocumentResolver.getDocumentUri(target)
            } catch (e: ResolverException) {
                throw e.toFileSystemException(target.toString())
            }
            copyOptions.progressListener?.invokeWithSize(targetUri)
            return targetUri
        }
        val targetExists = DocumentResolver.exists(target)
        if (targetExists) {
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(target.toString())
            }
            refuseFileFolderReplace(source, target)
            try {
                DocumentResolver.remove(target)
            } catch (e: ResolverException) {
                throw e.toFileSystemException(target.toString())
            }
        }
        return try {
            DocumentResolver.move(
                source, target, copyOptions.atomicMove, copyOptions.progressIntervalMillis,
                copyOptions.progressListener
            )
        } catch (e: ResolverException) {
            (e.cause as? InterruptedIOException)?.let { throw it }
            throw e.toFileSystemException(source.toString(), target.toString())
        }
    }

    private fun refuseFileFolderReplace(source: DocumentPath, target: DocumentPath) {
        val sourceIsDirectory = try {
            DocumentResolver.getMimeType(source) == MimeType.DIRECTORY.value
        } catch (e: ResolverException) {
            throw e.toFileSystemException(source.toString())
        }
        val targetIsDirectory = try {
            DocumentResolver.getMimeType(target) == MimeType.DIRECTORY.value
        } catch (e: ResolverException) {
            throw e.toFileSystemException(target.toString())
        }
        if (sourceIsDirectory != targetIsDirectory) {
            throw FileSystemException(
                source.toString(), target.toString(),
                "Cannot replace a directory with a file"
            )
        }
    }

    private fun ((Long) -> Unit).invokeWithSize(uri: Uri) {
        val size = try {
            DocumentResolver.getSize(uri)
        } catch (e: ResolverException) {
            e.printStackTrace()
            return
        } ?: return
        this(size)
    }
}
