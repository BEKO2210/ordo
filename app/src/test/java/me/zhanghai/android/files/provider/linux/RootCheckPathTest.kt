/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.provider.linux

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The root check judges where a path leads, never its spelling, and takes root only when nothing else gets there:
 * never because of a symlink in a place the app can write to (root would follow it: a confused deputy).
 */
class RootCheckPathTest {
    private val base = Files.createTempDirectory("rootcheck").toFile().canonicalFile
    private val volume = File(base, "storage/emulated/0").apply { mkdirs() }
    private val music = File(volume, "Music").apply { mkdirs() }
    private val secret = File(base, "data/secret").apply { mkdirs() }

    // Only the volume is reachable without root.
    private fun check(file: File, noFollowLinks: Boolean): RootCheck =
        rootCheck(file, noFollowLinks, { !it.startsWith(volume) }, ::readlink)

    // lstat + readlink on the JVM; LinuxPath uses Os.
    private fun readlink(file: File): String? =
        if (Files.isSymbolicLink(file.toPath())) Files.readSymbolicLink(file.toPath()).toString() else null

    private fun root(file: File, noFollowLinks: Boolean): Boolean = check(file, noFollowLinks) == RootCheck.ROOT

    private fun link(at: File, to: File): File = at.also { Files.createSymbolicLink(it.toPath(), to.toPath()) }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    @Test
    fun dotDotOutOfTheVolumeNeedsRoot() {
        assertTrue(root(File(volume, "../../../data/secret"), false))
        assertTrue(root(File(volume, "../../../data/secret"), true))
        assertTrue(root(File(volume, "../.."), true))
        assertFalse(root(File(volume, "Music/../Music"), false))
    }

    @Test
    fun linkInsideToOutsideNeverTakesRoot() {
        // Anyone who can write to the volume could plant it: the kernel judges, root does not follow it.
        val link = link(File(volume, "link"), secret)
        assertFalse(root(link, false))
        assertFalse(root(link, true))
        assertFalse(root(File(link, "x"), false))
    }

    @Test
    fun plantedLinksAreTold() {
        val link = link(File(volume, "link"), secret)
        val dir = link(File(volume, "dir"), secret)
        assertEquals(RootCheck.SHARED_SYMLINK, check(link, false))
        assertEquals(RootCheck.SHARED_SYMLINK, check(File(dir, "x"), false))
        // Not followed (rename, unlink, a copy of the link itself): only the entry counts.
        assertEquals(RootCheck.LOCAL, check(link, true))
        assertEquals(RootCheck.LOCAL, check(File(music, "song.mp3"), false))
        // /sdcard is in /, which no app can write to: not a planted link.
        val sdcard = link(File(base, "sdcard"), volume)
        assertEquals(RootCheck.LOCAL, check(File(sdcard, "Music"), false))
    }

    @Test
    fun aProtectedLinkIntoAPlantedOneIsCaughtHopByHop() {
        // /secret/hop -> /volume/planted -> /secret: the planted link in the middle of the chain counts.
        val planted = link(File(volume, "planted"), secret)
        val hop = link(File(secret, "hop"), planted)
        assertEquals(RootCheck.SHARED_SYMLINK, check(hop, false))
        assertEquals(RootCheck.SHARED_SYMLINK, check(File(hop, "x"), false))
        // Relative targets the same way.
        val relative = File(secret, "rel").also {
            Files.createSymbolicLink(it.toPath(), java.nio.file.Paths.get("../../storage/emulated/0/planted"))
        }
        assertEquals(RootCheck.SHARED_SYMLINK, check(relative, false))
    }

    @Test
    fun aLinkLoopNeedsRoot() {
        val a = File(secret, "a")
        link(File(secret, "b"), a)
        link(a, File(secret, "b"))
        assertTrue(root(a, false))
    }

    @Test
    fun linkOutsideToInside() {
        val link = link(File(secret, "link"), music)
        assertFalse(root(link, false))
        // Deleting or renaming it touches the root-only directory, not the volume.
        assertTrue(root(link, true))
    }

    @Test
    fun linkToADirectory() {
        // Planted in the volume: root never reaches through it.
        val inside = link(File(volume, "dir"), secret)
        assertFalse(root(File(inside, "x"), false))
        assertFalse(root(File(inside, "x"), true))
        val outside = link(File(secret, "dir"), music)
        assertFalse(root(File(outside, "song.mp3"), false))
        assertFalse(root(File(outside, "song.mp3"), true))
    }

    @Test
    fun linkWithItsTargetGone() {
        // In the volume: no root, ever (root would create or open what it points to: O_CREAT follows the link).
        val planted = link(File(volume, "gone"), File(secret, "deleted"))
        assertFalse(root(planted, false))
        assertFalse(root(planted, true))
        // Outside: judged by where its target would be, hop by hop.
        val intoVolume = link(File(secret, "gone"), File(music, "deleted"))
        assertFalse(root(intoVolume, false))
        assertTrue(root(intoVolume, true))
        val stayingOutside = link(File(secret, "gone2"), File(secret, "deleted"))
        assertTrue(root(stayingOutside, false))
    }

    @Test
    fun newNameCountsAsItsEntry() {
        assertFalse(root(File(music, "new.mp3"), false))
        assertTrue(root(File(secret, "new"), false))
    }

    @Test
    fun unknownEntryOutsideNeedsRoot() {
        assertTrue(rootCheck(File(secret, "unknown"), false, { !it.startsWith(volume) }) { throw IOException() } == RootCheck.ROOT)
        // The planted-link rule only holds where lstat answers; elsewhere it is root, as for a path outside.
        assertTrue(rootCheck(File(music, "x"), false, { !it.startsWith(volume) }) { throw IOException() } == RootCheck.ROOT)
    }

    @Test
    fun sdcardAliasCountsAsTheVolume() {
        val sdcard = link(File(base, "sdcard"), volume)
        assertFalse(root(sdcard, false))
        assertFalse(root(File(sdcard, "Music"), false))
        assertFalse(root(File(sdcard, "Music"), true))
        // lstat or delete of /sdcard itself acts on the link in /, which is not the volume.
        assertTrue(root(sdcard, true))
    }
}
