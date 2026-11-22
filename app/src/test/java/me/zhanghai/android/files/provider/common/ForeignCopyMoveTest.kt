/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.provider.common

import java8.nio.file.Paths
import java8.nio.file.spi.FileSystemProvider
import me.zhanghai.android.files.provider.linux.LinuxFileSystemProvider
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

class ForeignCopyMoveTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun fileSystem() {
            runCatching { FileSystemProvider.installDefaultProvider(LinuxFileSystemProvider) }
        }
    }

    @Test
    fun partFileSitsNextToTheTarget() {
        val target = Paths.get("/storage/emulated/0/Download/clip.mp4")
        assertEquals(
            Paths.get("/storage/emulated/0/Download/.clip.mp4.ordo-part"),
            ForeignCopyMove.partFile(target)
        )
    }
}
