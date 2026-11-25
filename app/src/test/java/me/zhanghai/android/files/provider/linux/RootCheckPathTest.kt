/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.provider.linux

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * The root check judges what an operation touches, never the path's spelling: on the link itself (lstat, unlink,
 * rename, readlink) its entry in the canonical parent, on the target (stat, open) the canonical target, and nothing
 * (so root) when that cannot be resolved.
 */
class RootCheckPathTest {
    private val base = Files.createTempDirectory("rootcheck").toFile().canonicalFile
    private val volume = File(base, "storage/emulated/0").apply { mkdirs() }
    private val music = File(volume, "Music").apply { mkdirs() }
    private val secret = File(base, "data/secret").apply { mkdirs() }

    // lstat on the JVM; LinuxPath uses Os.lstat.
    private fun resolve(file: File, noFollowLinks: Boolean): File? =
        resolvedForRootCheck(file, noFollowLinks) { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }

    private fun link(at: File, to: File): File = at.also { Files.createSymbolicLink(it.toPath(), to.toPath()) }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    @Test
    fun dotDotOutOfTheVolumeLeadsOutOfIt() {
        val sneaky = File(volume, "../../../data/secret")
        assertEquals(secret, resolve(sneaky, false))
        assertEquals(secret, resolve(sneaky, true))
        assertEquals(base.resolve("storage"), resolve(File(volume, "../.."), true))
    }

    @Test
    fun linkInsideToOutside() {
        val link = link(File(volume, "link"), secret)
        assertEquals(secret, resolve(link, false))
        assertEquals(link, resolve(link, true))
    }

    @Test
    fun linkOutsideToInside() {
        val link = link(File(secret, "link"), music)
        assertEquals(music, resolve(link, false))
        // Deleting or renaming it touches the root-only directory, not the volume.
        assertEquals(link, resolve(link, true))
    }

    @Test
    fun linkToADirectoryLeadsInto() {
        val dir = link(File(volume, "dir"), secret)
        assertEquals(File(secret, "x"), resolve(File(dir, "x"), false))
        assertEquals(File(secret, "x"), resolve(File(dir, "x"), true))
    }

    @Test
    fun linkWithItsTargetGoneNeedsRootUnlessTheLinkItselfIsMeant() {
        val link = link(File(volume, "gone"), File(secret, "deleted"))
        assertNull(resolve(link, false))
        assertEquals(link, resolve(link, true))
    }

    @Test
    fun newNameCountsAsItsEntry() {
        assertEquals(File(music, "new.mp3"), resolve(File(music, "new.mp3"), false))
    }

    @Test
    fun unknownEntryNeedsRoot() {
        val unknown = File(volume, "unknown")
        assertNull(resolvedForRootCheck(unknown, false) { throw IOException("EACCES") })
    }

    @Test
    fun sdcardAliasCountsAsTheVolume() {
        val sdcard = link(File(base, "sdcard"), volume)
        assertEquals(volume, resolve(sdcard, false))
        assertEquals(music, resolve(File(sdcard, "Music"), false))
        assertEquals(music, resolve(File(sdcard, "Music"), true))
        // lstat of /sdcard itself reads the link in /, which is not the volume.
        assertEquals(sdcard, resolve(sdcard, true))
    }
}
