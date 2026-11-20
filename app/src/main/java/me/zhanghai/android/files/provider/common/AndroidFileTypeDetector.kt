/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.spi.FileTypeDetector
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.MpegTsSniffer
import me.zhanghai.android.files.file.forSpecialPosixFileType
import me.zhanghai.android.files.file.guessFromPath
import me.zhanghai.android.files.provider.linux.isLinuxPath
import java.io.IOException

object AndroidFileTypeDetector : FileTypeDetector() {
    @Throws(IOException::class)
    override fun probeContentType(path: Path): String {
        val attributes = path.readAttributes(BasicFileAttributes::class.java)
        return getMimeType(path, attributes)
    }

    fun getMimeType(path: Path, attributes: BasicFileAttributes): String {
        MimeType.forSpecialPosixFileType(attributes.posixFileType)?.let { return it.value }
        if (attributes.isDirectory) {
            return MimeType.DIRECTORY.value
        }
        if (attributes is ContentProviderFileAttributes) {
            attributes.mimeType()?.let { return it }
        }
        val guessed = MimeType.guessFromPath(path.toString()).value
        // Local ".ts" files only: a short read, never over the network or a content provider.
        if (guessed == TYPESCRIPT && path.isLinuxPath && attributes.isRegularFile &&
            MpegTsSniffer.isMpegTs(path.toString())
        ) {
            return MpegTsSniffer.MIME_TYPE
        }
        return guessed
    }

    private const val TYPESCRIPT = "application/typescript"
}
