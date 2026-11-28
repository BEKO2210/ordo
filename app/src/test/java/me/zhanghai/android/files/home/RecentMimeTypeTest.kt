/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import java8.nio.file.Paths
import me.zhanghai.android.files.file.asMimeType
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentMimeTypeTest {
    @Test
    fun reportedTypeWins() {
        assertEquals(
            "application/pdf".asMimeType(),
            recentMimeType(Paths.get("/storage/emulated/0/Download/file.bin"), "application/pdf")
        )
    }

    @Test
    fun missingOrInvalidTypeFallsBackToExtension() {
        val path = Paths.get("/storage/emulated/0/Download/settings.yml")
        val expected = "application/yaml".asMimeType()
        assertEquals(expected, recentMimeType(path, null))
        assertEquals(expected, recentMimeType(path, "not-a-mime-type"))
        assertEquals(expected, recentMimeType(path, "application/octet-stream"))
    }
}
