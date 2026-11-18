/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio.lyrics

import java.io.ByteArrayOutputStream
import java.io.File
import me.zhanghai.android.files.viewer.audio.SongTagIo
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.ID3v23Frame
import org.jaudiotagger.tag.id3.framebody.FrameBodySYLT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Source order, SYLT decoding, the cache and the charset of `.lrc` files. */
class LyricsRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun song(): File {
        val file = File(folder.root, "Song.mp3")
        javaClass.getResourceAsStream("/tags/song.mp3")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    // SYLT content: text, terminator, 4-byte millisecond timestamp, repeated (ISO-8859-1 or UTF-8).
    private fun syltData(vararg entries: Pair<String, Int>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((text, ms) in entries) {
            out.write(text.toByteArray(Charsets.UTF_8))
            out.write(0)
            out.write(byteArrayOf((ms ushr 24).toByte(), (ms ushr 16).toByte(), (ms ushr 8).toByte(), ms.toByte()))
        }
        return out.toByteArray()
    }

    private val sylt = SyltLyrics.decode(3, 2, 0, syltData("Hal" to 1000, "lo" to 1500, "\nWelt" to 3000))

    @Test
    fun syltDecodesIntoLinesAndWords() {
        val lines = sylt!!.toSyncedLyrics(trimEnabled = true).text
        assertEquals(listOf("Hallo", "Welt"), lines.map { it.text })
        assertEquals(listOf(1000uL, 3000uL), lines.map { it.start })
        assertEquals(2, lines[0].words!!.size)
    }

    @Test
    fun brokenSyltIsNull() {
        assertNull(SyltLyrics.decode(3, 2, 0, byteArrayOf(0x41, 0, 0, 1)))
        assertNull(SyltLyrics.decode(9, 2, 0, syltData("A" to 1)))
    }

    @Test
    fun sourcePriority() {
        val all = EmbeddedLyrics(sylt, "[00:09.00]Vorbis", "[00:08.00]Uslt")
        assertEquals(LyricsSource.SIDECAR_LRC, LyricsRepository.pick("[00:01.00]Datei") { error("not read") }?.source)
        // A blank or empty sidecar falls through to the file.
        assertEquals(LyricsSource.EMBEDDED_SYNCED, LyricsRepository.pick(" \n") { all }?.source)
        assertEquals("Hallo", LyricsRepository.pick(null) { all }!!.lines[0].text)
        assertEquals("Vorbis", LyricsRepository.pick(null) { EmbeddedLyrics(null, "[00:09.00]Vorbis", "[00:08.00]Uslt") }!!.lines[0].text)
        // SYNCEDLYRICS without timestamps is not synced; the plain field wins then.
        assertEquals("Uslt", LyricsRepository.pick(null) { EmbeddedLyrics(null, "kein Takt", "[00:08.00]Uslt") }!!.lines[0].text)
        val plain = LyricsRepository.pick(null) { EmbeddedLyrics(plain = "Nur\nText") }!!
        assertEquals(LyricsSource.EMBEDDED_UNSYNCED, plain.source)
        assertEquals(listOf("Nur", "Text"), plain.text)
        assertNull(LyricsRepository.pick(null) { EmbeddedLyrics() })
        assertNull(LyricsRepository.pick(null) { EmbeddedLyrics(plain = "x".repeat(LyricsRepository.MAX_BYTES + 1)) })
    }

    @Test
    fun readsTheFileThenTheSidecarAndCachesUntilEitherChanges() {
        val file = song()
        SongTagIo.isEditableFormat(file) // jaudiotagger set-up
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault as AbstractID3v2Tag
        tag.setField(FieldKey.LYRICS, "Ungetaktet")
        tag.setFrame(ID3v23Frame("SYLT").apply {
            body = FrameBodySYLT(3, "deu", 2, 1, "", syltData("Getaktet" to 2000))
        })
        audio.commit()

        val embedded = LyricsRepository.loadBlocking(file)!!
        assertEquals(LyricsSource.EMBEDDED_SYNCED, embedded.source)
        assertEquals("Getaktet", embedded.lines[0].text)
        assertEquals(2000uL, embedded.lines[0].start)
        assertSame(embedded, LyricsRepository.loadBlocking(file))

        // Another case of the name still counts as the song's .lrc.
        val lrc = File(folder.root, "song.LRC").apply { writeText("[00:01.00]Aus der Datei") }
        val sidecar = LyricsRepository.loadBlocking(file)!!
        assertEquals(LyricsSource.SIDECAR_LRC, sidecar.source)
        assertSame(sidecar, LyricsRepository.loadBlocking(file))

        lrc.writeText("[00:01.00]Geändert")
        lrc.setLastModified(lrc.lastModified() + 5000)
        assertEquals("Geändert", LyricsRepository.loadBlocking(file)!!.lines[0].text)

        val before = LyricsRepository.loadBlocking(file)
        file.setLastModified(file.lastModified() + 5000)
        assertNotSame(before, LyricsRepository.loadBlocking(file))
    }

    @Test
    fun tooLargeSidecarIsIgnored() {
        val file = File(folder.root, "leer.mp3").apply { writeBytes(ByteArray(0)) }
        File(folder.root, "leer.lrc").writeText("[00:01.00]A\n" + "x".repeat(LyricsRepository.MAX_BYTES))
        assertNull(LyricsRepository.loadBlocking(file))
    }

    @Test
    fun charsets() {
        val text = "Grüße ÄÖÜ ß"
        val bomUtf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
        assertEquals(text, LyricsRepository.decodeText(bomUtf8))
        val bomLe = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        assertEquals(text, LyricsRepository.decodeText(bomLe))
        val bomBe = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        assertEquals(text, LyricsRepository.decodeText(bomBe))
        assertEquals(text, LyricsRepository.decodeText(text.toByteArray(Charsets.UTF_8)))
        // Not valid UTF-8: read as Windows-1252, the usual encoding of old German .lrc files.
        assertEquals(text, LyricsRepository.decodeText(text.toByteArray(charset("windows-1252"))))
        assertEquals("", LyricsRepository.decodeText(ByteArray(0)))
    }

    @Test
    fun sidecarInWindows1252IsRead() {
        val file = File(folder.root, "alt.mp3").apply { writeBytes(ByteArray(0)) }
        File(folder.root, "alt.lrc").writeBytes("[00:01.00]Grüße".toByteArray(charset("windows-1252")))
        assertEquals("Grüße", LyricsRepository.loadBlocking(file)!!.lines[0].text)
    }
}
