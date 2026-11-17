/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Every editable format: write, read back, cover round trip, unicode, clearing, and a failed write. */
class SongTagsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val formats = listOf("mp3", "flac", "ogg", "m4a")

    // A real 1×1 PNG: the cover must come back byte for byte.
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53,
        0xDE.toByte(), 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54, 0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(),
        0xCF.toByte(), 0xC0.toByte(), 0x00, 0x00, 0x03, 0x01, 0x01, 0x00, 0x18, 0xDD.toByte(), 0x8D.toByte(),
        0xB0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
    )

    private fun fixture(extension: String): File {
        val file = File(folder.root, "song.$extension")
        javaClass.getResourceAsStream("/tags/song.$extension")!!.use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    private val backups get() = File(folder.root, "backups")

    @Test
    fun everyFormatWritesAndReadsBackAllFields() {
        for (extension in formats) {
            val file = fixture(extension)
            val values = mapOf(
                TagField.TITLE to "Nachtfahrt – Grüße an Zoë ♪", TagField.ARTIST to "Ŝtraße Ensemble",
                TagField.ALBUM to "日本の夜", TagField.ALBUM_ARTIST to "Various", TagField.TRACK to "3",
                TagField.TRACK_TOTAL to "12", TagField.DISC to "1", TagField.YEAR to "2026", TagField.GENRE to "Jazz",
                TagField.LYRICS to "Erste Zeile\nZweite Zeile"
            )
            SongTagIo.write(file, TagChanges(values, CoverChange.Set(Cover(png, "image/png"))), backups)
            val read = SongTagIo.read(file)
            for ((field, value) in values) {
                if (field.numeric) {
                    assertEquals("$extension $field", value.toInt(), read[field].toInt())
                } else {
                    assertEquals("$extension $field", value, read[field])
                }
            }
            assertArrayEquals("$extension cover", png, read.cover?.data)
        }
    }

    @Test
    fun unchangedFieldsStayAndClearedFieldsGo() {
        for (extension in formats) {
            val file = fixture(extension)
            assertEquals("Fixture", SongTagIo.read(file)[TagField.TITLE])
            SongTagIo.write(file, TagChanges(mapOf(TagField.ALBUM to "Only this")), backups)
            var read = SongTagIo.read(file)
            assertEquals("$extension keeps the title", "Fixture", read[TagField.TITLE])
            assertEquals("$extension keeps the artist", "Ordo", read[TagField.ARTIST])
            assertEquals("Only this", read[TagField.ALBUM])
            SongTagIo.write(file, TagChanges(mapOf(TagField.ARTIST to "")), backups)
            read = SongTagIo.read(file)
            assertEquals("$extension cleared the artist", "", read[TagField.ARTIST])
            assertEquals("Fixture", read[TagField.TITLE])
        }
    }

    @Test
    fun coverCanBeRemoved() {
        for (extension in formats) {
            val file = fixture(extension)
            SongTagIo.write(file, TagChanges(emptyMap(), CoverChange.Set(Cover(png, "image/png"))), backups)
            SongTagIo.write(file, TagChanges(emptyMap(), CoverChange.Remove), backups)
            assertNull("$extension cover removed", SongTagIo.read(file).cover)
        }
    }

    @Test
    fun aFailedWriteLeavesTheOriginalIntact() {
        for (extension in formats) {
            val file = fixture(extension)
            val before = file.readBytes()
            try {
                SongTagIo.write(file, TagChanges(mapOf(TagField.TITLE to "Never")), backups) {
                    throw IOException("simulated failure before the replace")
                }
                fail("the write must fail")
            } catch (_: IOException) {
            }
            assertArrayEquals("$extension untouched", before, file.readBytes())
            assertTrue("no temp file left", folder.root.list()!!.none { it.startsWith(".ordo-edit") })
        }
    }

    @Test
    fun undoRestoresTheOriginalBytes() {
        val file = fixture("mp3")
        val before = file.readBytes()
        val backup = SongTagIo.write(file, TagChanges(mapOf(TagField.TITLE to "Changed")), backups)
        assertFalse(before.contentEquals(file.readBytes()))
        SongTagIo.restore(file, backup)
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun batchWritesOnlyChangedFields() {
        val first = mapOf(TagField.TITLE to "One", TagField.ARTIST to "Band", TagField.YEAR to "2001")
        val second = mapOf(TagField.TITLE to "Two", TagField.ARTIST to "Band", TagField.YEAR to "2002")
        val tags = listOf(first, second).map { SongTags(TagField.entries.associateWith { f -> it[f].orEmpty() }, null) }
        val merged = SongTagIo.merge(tags)
        assertNull("titles differ", merged[TagField.TITLE])
        assertEquals("Band", merged[TagField.ARTIST])
        // The user only typed an album artist and left the differing title empty.
        val edited = TagField.entries.associateWith { merged[it].orEmpty() }.toMutableMap()
        edited[TagField.ALBUM_ARTIST] = "Band"
        val changes = SongTagIo.changes(merged, edited, CoverChange.Keep)
        assertEquals(mapOf(TagField.ALBUM_ARTIST to "Band"), changes.fields)
        // Written into two real files, each keeps its own title.
        val a = fixture("mp3")
        val b = File(folder.root, "b.mp3").also { a.copyTo(it) }
        SongTagIo.write(a, TagChanges(mapOf(TagField.TITLE to "One")), backups)
        SongTagIo.write(b, TagChanges(mapOf(TagField.TITLE to "Two")), backups)
        listOf(a, b).forEach { SongTagIo.write(it, changes, backups) }
        assertEquals("One", SongTagIo.read(a)[TagField.TITLE])
        assertEquals("Two", SongTagIo.read(b)[TagField.TITLE])
        assertEquals("Band", SongTagIo.read(b)[TagField.ALBUM_ARTIST])
    }

    @Test
    fun validationCatchesYearAndNumbers() {
        assertTrue(SongTagIo.validate(mapOf(TagField.YEAR to "2026", TagField.TRACK to "3", TagField.TRACK_TOTAL to "9")).isEmpty())
        assertEquals(TagProblem.YEAR, SongTagIo.validate(mapOf(TagField.YEAR to "26"))[TagField.YEAR])
        assertEquals(TagProblem.NUMBER, SongTagIo.validate(mapOf(TagField.DISC to "x"))[TagField.DISC])
        assertEquals(
            TagProblem.TRACK_ABOVE_TOTAL,
            SongTagIo.validate(mapOf(TagField.TRACK to "10", TagField.TRACK_TOTAL to "9"))[TagField.TRACK]
        )
        assertTrue(SongTagIo.validate(mapOf(TagField.YEAR to "", TagField.TRACK to "")).isEmpty())
    }

    @Test
    fun opusAndWavAreNotEditable() {
        assertFalse(SongTagIo.isEditableFormat(File("a.opus")))
        assertFalse(SongTagIo.isEditableFormat(File("a.wav")))
        assertTrue(SongTagIo.isEditableFormat(File("a.FLAC")))
    }
}
