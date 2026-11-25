/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.provider.linux.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class MediaScannerPathTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val root by lazy { tmp.newFolder("storage", "emulated", "0").canonicalFile }
    private val sdCard by lazy { tmp.newFolder("storage", "ABCD-1234").canonicalFile }
    private val appData by lazy { tmp.newFolder("data", "data", "me.zhanghai.android.files").canonicalFile }
    private val volumes by lazy { listOf(root.path, sdCard.path) }

    @Test
    fun fileInsideAVolumeIsScanned() {
        val song = File(root, "Music/song.mp3")
        assertEquals(song.path, MediaScanner.scannablePath(song.path, volumes))
        val photo = File(sdCard, "DCIM/a.jpg")
        assertEquals(photo.path, MediaScanner.scannablePath(photo.path, volumes))
    }

    @Test
    fun deletedFileInsideAVolumeIsStillScanned() {
        val gone = File(root, "Download/gone.mp3")
        assertEquals(gone.path, MediaScanner.scannablePath(gone.path, volumes))
    }

    @Test
    fun dotDotOutOfTheVolumeIsRefused() {
        assertNull(MediaScanner.scannablePath("${root.path}/../../../data/data/x/db", volumes))
        assertNull(MediaScanner.scannablePath("${root.path}/..", volumes))
    }

    @Test
    fun dotDotStayingInsideIsNormalized() {
        val song = File(root, "Music/song.mp3")
        assertEquals(song.path, MediaScanner.scannablePath("${root.path}/Movies/../Music/song.mp3", volumes))
    }

    @Test
    fun symlinkOutOfTheVolumeIsRefused() {
        val secret = File(appData, "secret.db").apply { writeText("x") }
        val link = File(root, "innocent.mp3")
        Files.createSymbolicLink(link.toPath(), secret.toPath())
        assertNull(MediaScanner.scannablePath(link.path, volumes))
        val dirLink = File(root, "data")
        Files.createSymbolicLink(dirLink.toPath(), appData.toPath())
        assertNull(MediaScanner.scannablePath("${dirLink.path}/secret.db", volumes))
    }

    @Test
    fun pathsOutsideAnyVolumeAreRefused() {
        assertNull(MediaScanner.scannablePath(File(appData, "secret.db").path, volumes))
        assertNull(MediaScanner.scannablePath("/proc/self/environ", volumes))
        assertNull(MediaScanner.scannablePath("/", volumes))
        assertNull(MediaScanner.scannablePath(File(root, "x.mp3").path, emptyList()))
    }

    @Test
    fun foreignVolumeAndPrefixLookalikeAreRefused() {
        val other = tmp.newFolder("mnt", "usb").canonicalFile
        assertNull(MediaScanner.scannablePath(File(other, "a.mp3").path, volumes))
        // /storage/emulated/0evil must not pass as being inside /storage/emulated/0.
        assertNull(MediaScanner.scannablePath("${root.path}evil/a.mp3", volumes))
    }
}
