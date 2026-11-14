/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.filelist

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java8.nio.file.FileVisitResult
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.SimpleFileVisitor
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.provider.common.newInputStream

// Name search with optional regex plus full-text search, used when the plain name search is not enough.
class ContentSearch(query: String, useRegex: Boolean, private val searchContent: Boolean) {
    private val pattern: Regex = if (useRegex) {
        try {
            Regex(query, RegexOption.IGNORE_CASE)
        } catch (e: IllegalArgumentException) {
            Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
        }
    } else {
        Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
    }

    fun nameMatches(name: String): Boolean = pattern.containsMatchIn(name)

    // Binary files (a NUL byte in the first 4 KiB) are skipped; lines are matched one by one.
    @Throws(IOException::class)
    fun contentMatches(input: InputStream): Boolean {
        val buffered = input.buffered()
        buffered.mark(BINARY_CHECK_SIZE)
        val head = ByteArray(BINARY_CHECK_SIZE)
        val headSize = buffered.readNBytesCompat(head)
        if (head.take(headSize).any { it == 0.toByte() }) {
            return false
        }
        buffered.reset()
        buffered.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (Thread.interrupted()) {
                    throw InterruptedIOException()
                }
                if (pattern.containsMatchIn(line)) {
                    return true
                }
            }
        }
        return false
    }

    @Throws(IOException::class)
    fun search(directory: Path, intervalMillis: Long, listener: (List<Path>) -> Unit) {
        val paths = mutableListOf<Path>()
        var lastNotifyMillis = System.currentTimeMillis()
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                dir: Path,
                attributes: BasicFileAttributes
            ): FileVisitResult {
                if (dir != directory && nameMatches(dir.fileName.toString())) {
                    paths.add(dir)
                }
                return check()
            }

            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                val matches = nameMatches(file.fileName.toString()) || searchContent &&
                    attributes.isRegularFile && attributes.size() <= MAX_CONTENT_SIZE &&
                    try {
                        file.newInputStream().use { contentMatches(it) }
                    } catch (e: InterruptedIOException) {
                        throw e
                    } catch (e: IOException) {
                        false
                    }
                if (matches) {
                    paths.add(file)
                }
                return check()
            }

            override fun visitFileFailed(file: Path, exception: IOException): FileVisitResult =
                check()

            private fun check(): FileVisitResult {
                if (Thread.interrupted()) {
                    throw InterruptedIOException()
                }
                val now = System.currentTimeMillis()
                if (paths.isNotEmpty() && now - lastNotifyMillis >= intervalMillis) {
                    listener(paths.toList())
                    paths.clear()
                    lastNotifyMillis = now
                }
                return FileVisitResult.CONTINUE
            }
        })
        if (paths.isNotEmpty()) {
            listener(paths.toList())
        }
    }

    private fun InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read < 0) {
                break
            }
            total += read
        }
        return total
    }

    companion object {
        private const val BINARY_CHECK_SIZE = 4096
        private const val MAX_CONTENT_SIZE = 20L * 1024 * 1024
    }
}
