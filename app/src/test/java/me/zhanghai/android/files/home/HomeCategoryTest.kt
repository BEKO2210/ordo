/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeCategoryTest {
    @Test fun `mime types map to media categories`() {
        assertEquals(setOf(HomeCategory.IMAGES), HomeCategory.classify("image/jpeg", "photo.jpg", "DCIM/"))
        assertEquals(setOf(HomeCategory.VIDEOS), HomeCategory.classify("video/mp4", "clip.mp4", "Movies/"))
        assertEquals(setOf(HomeCategory.AUDIO), HomeCategory.classify("audio/mpeg", "song.mp3", "Music/"))
    }

    @Test fun `special extensions map without mime type`() {
        assertEquals(setOf(HomeCategory.APKS), HomeCategory.classify(null, "app.APK", null))
        assertEquals(setOf(HomeCategory.ARCHIVES), HomeCategory.classify(null, "backup.tar.gz", null))
        assertEquals(setOf(HomeCategory.DOCUMENTS), HomeCategory.classify(null, "notes.pdf", null))
    }

    @Test fun `downloads also keep their file type`() {
        assertEquals(
            setOf(HomeCategory.DOWNLOADS, HomeCategory.IMAGES),
            HomeCategory.classify("image/png", "download.png", "Download/screenshots/")
        )
        assertEquals(
            setOf(HomeCategory.DOWNLOADS),
            HomeCategory.classify(null, "blob.bin", "Download/")
        )
    }

    @Test fun `unknown files are not categorized`() {
        assertEquals(emptySet<HomeCategory>(), HomeCategory.classify("application/octet-stream", "blob.bin", "Other/"))
    }
}
