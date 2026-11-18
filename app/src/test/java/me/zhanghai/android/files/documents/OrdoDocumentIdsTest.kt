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
    fun appFoldersStayPrivateInAnyCase() {
        // Shared storage is case-insensitive: every spelling of Android/data|obb|media is the same folder.
        for (path in listOf(
            "/storage/emulated/0/ANDROID/Data/com.example", "/storage/emulated/0/android/OBB",
            "/storage/emulated/0/Android/MEDIA/x.jpg", "/storage/emulated/0/aNdRoId/dAtA", "/DATA/data/com.example"
        )) {
            assertTrue(path, OrdoDocumentIds.isPrivate(File(path)))
        }
        assertFalse(OrdoDocumentIds.isPrivate(File("/storage/emulated/0/Android")))
        assertFalse(OrdoDocumentIds.isPrivate(File("/storage/emulated/0/Music/Android/database.mp3")))
        // A bookmark inside Android/ in any spelling is refused as a place, and nothing under it opens by id.
        val mixed = File(storage, "android/OBB/game").apply { mkdirs() }
        assertEquals(emptyList<File>(), OrdoDocumentIds.bases(listOf(mixed)))
        assertNull(OrdoDocumentIds.fileOf(OrdoDocumentIds.idOf(mixed), listOf(storage)))
    }

    @Test
    fun aHiddenFileIsNeverOpenedByItsId() {
        val hidden = File(music, ".nomedia").apply { writeText("") }
        val inHiddenFolder = File(music, ".thumbnails/a.jpg").apply { parentFile!!.mkdirs(); writeText("x") }
        assertNull(OrdoDocumentIds.fileOf(OrdoDocumentIds.idOf(hidden), bases))
        assertNull(OrdoDocumentIds.fileOf(OrdoDocumentIds.idOf(inHiddenFolder), bases))
        assertEquals(emptyList<File>(), OrdoDocumentIds.bases(listOf(inHiddenFolder.parentFile!!)))
    }

    @Test
    fun recentFilesAreCheckedLikeBookmarksAndCapped() {
        val files = (1..60).map { File(secret, "r$it.txt").apply { writeText("x") } }
        val hidden = File(secret, ".hidden.txt").apply { writeText("x") }
        val appData = File(storage, "Android/data/com.example/f.txt").apply { parentFile!!.mkdirs(); writeText("x") }
        val recent = OrdoDocumentIds.recentFiles(listOf(appData, hidden, secret) + files)
        assertEquals(OrdoDocumentIds.RECENT_LIMIT, recent.size)
        assertEquals(files.take(OrdoDocumentIds.RECENT_LIMIT), recent)
        // A recent file opens by its id although it lies outside every bookmark; a neighbour does not.
        assertEquals(files[0], OrdoDocumentIds.fileOf(OrdoDocumentIds.idOf(files[0]), bases, recent))
        assertNull(OrdoDocumentIds.fileOf(OrdoDocumentIds.idOf(secretFile), bases, recent))
        assertNull(OrdoDocumentIds.fileOf(OrdoDocumentIds.idOf(files[59]), bases, recent))
    }

    @Test
    fun thumbnailsDecodeSubsampledNeverInFull() {
        assertEquals(1, OrdoDocumentIds.sampleSize(400, 300, 512))
        assertEquals(8, OrdoDocumentIds.sampleSize(4000, 3000, 512))
        assertEquals(16, OrdoDocumentIds.sampleSize(8000, 6000, 512))
        assertTrue(OrdoDocumentIds.sampleSize(100, 100, 0) >= 1)
        // An extreme panorama: the longer side decides, so it is not decoded at nearly full size.
        val sample = OrdoDocumentIds.sampleSize(100_000, 600, 512)
        assertTrue("$sample", 100_000 / sample <= 512)
        // Huge or missing bounds get no thumbnail at all.
        assertTrue(OrdoDocumentIds.canThumbnail(8000, 6000))
        assertFalse(OrdoDocumentIds.canThumbnail(10_000, 10_000))
        assertFalse(OrdoDocumentIds.canThumbnail(100_000, 600_000))
        assertFalse(OrdoDocumentIds.canThumbnail(0, 600))
    }

    @Test
    fun onlyTheBookmarkPlaceAllowsTreeGrants() {
        assertTrue(OrdoDocumentIds.supportsTree(OrdoDocumentIds.ROOT))
        assertFalse(OrdoDocumentIds.supportsTree(OrdoDocumentIds.RECENT))
        val provider = File("src/main/java/me/zhanghai/android/files/documents/OrdoDocumentsProvider.kt").readText()
        assertTrue(provider.contains("if (parentDocumentId == OrdoDocumentIds.RECENT) return false"))
        assertTrue(provider.contains("if (OrdoDocumentIds.supportsTree(documentId)) Root.FLAG_SUPPORTS_IS_CHILD else 0"))
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
