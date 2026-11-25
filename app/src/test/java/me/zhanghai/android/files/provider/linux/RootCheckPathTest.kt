/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.provider.linux

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The root check judges where a path leads: never by its spelling ("..", symlinks). */
class RootCheckPathTest {
    private val base = Files.createTempDirectory("rootcheck").toFile().canonicalFile
    private val volume = File(base, "storage/emulated/0").apply { mkdirs() }
    private val secret = File(base, "data/secret").apply { mkdirs() }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    @Test
    fun dotDotOutOfTheVolumeLeadsOutOfIt() {
        val sneaky = File(volume, "../../../data/secret")
        val resolved = resolvedForRootCheck(sneaky, false)!!
        assertEquals(secret, resolved)
        assertFalse(resolved.startsWith(volume))
    }

    @Test
    fun symlinkInTheVolumeLeadsToItsTarget() {
        val link = File(volume, "link")
        Files.createSymbolicLink(link.toPath(), secret.toPath())
        assertEquals(secret, resolvedForRootCheck(link, false))
        assertEquals(secret, resolvedForRootCheck(File(link, "x"), false)!!.parentFile)
    }

    @Test
    fun symlinkToTheVolumeCountsAsTheVolume() {
        val sdcard = File(base, "sdcard")
        Files.createSymbolicLink(sdcard.toPath(), volume.toPath())
        assertEquals(File(volume, "Music"), resolvedForRootCheck(File(sdcard, "Music"), false))
    }

    @Test
    fun attributeAccessReadsTheLinkItselfInItsResolvedParent() {
        val link = File(volume, "link")
        Files.createSymbolicLink(link.toPath(), secret.toPath())
        assertEquals(link, resolvedForRootCheck(link, true))
        // The parent is resolved even then: "../" in front of the name does not stay in the volume.
        assertEquals(File(base, "data/secret"), resolvedForRootCheck(File(volume, "../../../data/secret"), true))
    }

    @Test
    fun attributeAccessOnDotDotResolvesTheWholePath() {
        assertEquals(base.resolve("storage"), resolvedForRootCheck(File(volume, "../.."), true))
    }
}
