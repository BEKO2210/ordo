/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio.lyrics

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.viewer.audio.MusicSong
import me.zhanghai.android.files.viewer.audio.SongTagIo
import me.zhanghai.android.files.viewer.audio.lyrics.SemanticLyrics.SyncedLyrics
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodySYLT
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag

/** What the audio file itself carries; each part is null when missing or too large. */
class EmbeddedLyrics(val sylt: SyltLyrics? = null, val synced: String? = null, val plain: String? = null)

/**
 * Finds a song's lyrics: a `.lrc` next to it (same name, any case), then synced lyrics in the file (ID3 SYLT, Vorbis
 * SYNCEDLYRICS), then the file's lyrics field (ID3 USLT, Vorbis LYRICS, MP4 ©lyr), which may itself be LRC. Results,
 * also "none", are cached per file until the song or its `.lrc` changes. Never touches the files.
 */
object LyricsRepository {
    const val MAX_BYTES = 512 * 1024
    private const val CACHE_SIZE = 32

    private class Entry(val stamp: String, val lyrics: Lyrics?)

    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > CACHE_SIZE
    }

    suspend fun load(song: MusicSong): Lyrics? = load(File(song.path))

    suspend fun load(file: File): Lyrics? = withContext(Dispatchers.IO) { loadBlocking(file) }

    internal fun loadBlocking(file: File): Lyrics? {
        val sidecar = findSidecar(file)
        val stamp = "${file.lastModified()}|${sidecar?.path}|${sidecar?.lastModified()}"
        synchronized(cache) { cache[file.path]?.takeIf { it.stamp == stamp }?.let { return it.lyrics } }
        val sidecarText = sidecar?.takeIf { it.length() <= MAX_BYTES }
            ?.let { runCatching { decodeText(it.readBytes()) }.getOrNull() }
        val lyrics = pick(sidecarText) { readEmbedded(file) }
        synchronized(cache) { cache[file.path] = Entry(stamp, lyrics) }
        return lyrics
    }

    internal fun findSidecar(file: File): File? {
        val name = file.nameWithoutExtension + ".lrc"
        val names = file.parentFile?.list() ?: return null
        val match = names.firstOrNull { it == name } ?: names.firstOrNull { it.equals(name, ignoreCase = true) }
        return match?.let { File(file.parentFile, it) }?.takeIf { it.isFile }
    }

    /** The first source that has lyrics; [embedded] is only read when the sidecar has none. */
    internal fun pick(sidecar: String?, embedded: () -> EmbeddedLyrics): Lyrics? {
        parse(sidecar)?.let { return Lyrics(it, LyricsSource.SIDECAR_LRC) }
        val inFile = embedded()
        inFile.sylt?.let { sylt -> runCatching { sylt.toSyncedLyrics(trimEnabled = true) }.getOrNull() }
            ?.takeIf { it.text.any { line -> line.text.isNotBlank() } }
            ?.let { return Lyrics(it, LyricsSource.EMBEDDED_SYNCED) }
        (parse(inFile.synced) as? SyncedLyrics)?.let { return Lyrics(it, LyricsSource.EMBEDDED_SYNCED) }
        return parse(inFile.plain)?.let {
            Lyrics(it, if (it is SyncedLyrics) LyricsSource.EMBEDDED_SYNCED else LyricsSource.EMBEDDED_UNSYNCED)
        }
    }

    private fun parse(text: String?): SemanticLyrics? =
        text?.takeIf { it.isNotBlank() && it.length <= MAX_BYTES }
            ?.let { runCatching { parseLrc(it, trimEnabled = true, multiLineEnabled = false) }.getOrNull() }
            ?.takeIf { lyrics -> lyrics.unsyncedText.any { it.first.isNotBlank() } }

    /** A byte order mark wins; otherwise strict UTF-8, and Windows-1252 when that fails. */
    internal fun decodeText(bytes: ByteArray): String {
        fun startsWith(vararg bom: Int) = bytes.size >= bom.size && bom.indices.all { bytes[it] == bom[it].toByte() }
        return when {
            startsWith(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            startsWith(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            startsWith(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            // shortcut: no guessing of CJK legacy encodings (GBK, Shift-JIS), add a detector when users report them.
            else -> try {
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: CharacterCodingException) {
                String(bytes, Charset.forName("windows-1252"))
            }
        }
    }

    private fun readEmbedded(file: File): EmbeddedLyrics {
        // Also sets up jaudiotagger for Android (SongTagIo's init).
        if (!SongTagIo.isEditableFormat(file)) return EmbeddedLyrics()
        val audio = runCatching { AudioFileIO.read(file) }.getOrNull() ?: return EmbeddedLyrics()
        val tag = audio.tag ?: return EmbeddedLyrics()
        val sylt = (tag as? AbstractID3v2Tag)?.let { id3 ->
            listOf("SYLT", "SLT").flatMap { id ->
                when (val frames = id3.getFrame(id)) {
                    null -> emptyList()
                    is List<*> -> frames
                    else -> listOf(frames)
                }
            }.mapNotNull { (it as? AbstractID3v2Frame)?.body as? FrameBodySYLT }
                // Content type 1 is "lyrics"; other types (events, chords) are only a fallback.
                .sortedBy { if (it.contentType == 1) 0 else 1 }
                .firstNotNullOfOrNull { body ->
                    body.lyrics?.takeIf { it.size <= MAX_BYTES }?.let {
                        SyltLyrics.decode(
                            body.textEncoding.toInt(), body.timeStampFormat,
                            audio.audioHeader?.sampleRateAsNumber ?: 0, it
                        )
                    }
                }
        }
        val vorbis = tag as? VorbisCommentTag ?: (tag as? FlacTag)?.vorbisCommentTag
        return EmbeddedLyrics(
            sylt,
            vorbis?.let { runCatching { it.getFirst("SYNCEDLYRICS") }.getOrNull() },
            runCatching { tag.getFirst(FieldKey.LYRICS) }.getOrNull()
        )
    }
}
