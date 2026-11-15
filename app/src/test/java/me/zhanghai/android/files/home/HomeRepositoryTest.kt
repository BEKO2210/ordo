/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRepositoryTest {
    private val roots = listOf("/storage/emulated/0", "/storage/1234-5678")

    @Test
    fun relativeDir_primaryAndSecondaryVolume() {
        assertEquals("Download", HomeRepository.relativeDir("/storage/emulated/0/Download/a.pdf", roots))
        assertEquals("Download", HomeRepository.relativeDir("/storage/1234-5678/Download/a.pdf", roots))
        assertEquals("", HomeRepository.relativeDir("/storage/1234-5678/a.pdf", roots))
    }
}
