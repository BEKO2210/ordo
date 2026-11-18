/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.documents

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrdoDocumentIdsTest {
    private val storage = Files.createTempDirectory("ordo-docs").toFile().canonicalFile
    private val music = File(storage, "Music").apply { mkdirs() }
    private val song = File(music, "song.mp3").apply { writeText("x") }
    private val secret = File(storage, "Secret").apply { mkdirs() }
    private val secretFile = File(secret, "notes.txt").apply { writeText("x") }
    private val bases = OrdoDocumentIds.bases(listOf(music))

    @Test
    fun anIdLeadsBackToItsFileInsideAFavourite() {
        assertEquals(listOf(music), bases)
        assertEquals(song, OrdoDocumentIds.fileOf(OrdoDocumentIds.idOf(song), bases))
        assertEquals(music, OrdoDocumentIds.fileOf(OrdoDocumentIds.idOf(music), bases))
    }

    @Test
    fun noIdReachesOutsideTheFavourites() {
        // A sibling folder, a ".." walk out of the favourite, and a prefix trick ("Music2" is not inside "Music").
        assertNull(OrdoDocumentIds.fileOf(OrdoDocumentIds.idOf(secretFile), bases))
        assertNull(OrdoDocumentIds.fileOf("path:${music.path}/../Secret/notes.txt", bases))
        assertNull(OrdoDocumentIds.fileOf("path:${music.path}/../../etc/passwd", bases))
        val lookalike = File(storage, "Music2").apply { mkdirs() }
        assertNull(OrdoDocumentIds.fileOf(OrdoDocumentIds.idOf(lookalike), bases))
        // Malformed ids: other schemes, relative paths, NUL bytes, nothing.
        val malformed = listOf(
            "", "path:", "root/..", "path:Music/song.mp3", "path:${song.path}\u0000.jpg", "file:${song.path}"
        )
        for (id in malformed) {
            assertNull(id, OrdoDocumentIds.fileOf(id, bases))
        }
    }

    @Test
    fun aSymlinkCannotLeadOutOfAFavourite() {
        val link = File(music, "escape")
        Files.createSymbolicLink(link.toPath(), secret.toPath())
        assertNull(OrdoDocumentIds.fileOf("path:${link.path}/notes.txt", bases))
    }

    @Test
    fun onlyTheSystemMayQueryTheProvider() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val provider = Regex("""<provider\s+android:name="[^"]*OrdoDocumentsProvider"[\s\S]*?</provider>""")
            .find(manifest)!!.value
        assertTrue(provider, provider.contains("""android:permission="android.permission.MANAGE_DOCUMENTS""""))
        assertTrue(provider, provider.contains("android.content.action.DOCUMENTS_PROVIDER"))
        assertFalse(provider, provider.contains("android:writePermission"))
    }

    @Test
    fun appPrivateFoldersAreNeverShownEvenAsAFavourite() {
        assertTrue(OrdoDocumentIds.isPrivate(File("/data/data/com.beko2210.ordo/files")))
        assertTrue(OrdoDocumentIds.isPrivate(File("/storage/emulated/0/Android/data/com.example/cache")))
        assertTrue(OrdoDocumentIds.isPrivate(File("/storage/emulated/0/Android/obb")))
        assertFalse(OrdoDocumentIds.isPrivate(File("/storage/emulated/0/Download")))
        val appData = File(storage, "Android/data/com.example").apply { mkdirs() }
        assertEquals(emptyList<File>(), OrdoDocumentIds.bases(listOf(appData)))
    }
}
