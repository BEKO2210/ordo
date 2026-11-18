/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio.lyrics

import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.provider.common.getLastModifiedTime
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.size
import me.zhanghai.android.files.provider.linux.isLinuxPath
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

/** One file as the repository needs it: Ordo's [Path] in the app, java.io.File in unit tests. */
internal interface LyricsFile {
    /** Identifies the file across calls (its URI). */
    val key: String
    val name: String
    val lastModified: Long
    val size: Long

    /** The files in the same folder, or null when there is no folder to list (content:// from another app). */
    fun siblings(): List<LyricsFile>?

    fun newInputStream(): InputStream

    /** The file on local storage, for jaudiotagger; null for other sources, which are copied first. */
    fun toLocalFile(): File?
}

private class PathLyricsFile(private val path: Path) : LyricsFile {
    override val key: String get() = path.toUri().toString()
    override val name: String get() = path.fileName?.toString().orEmpty()
    override val lastModified: Long get() = path.getLastModifiedTime().toMillis()
    override val size: Long get() = path.size()
    override fun siblings(): List<LyricsFile>? = path.parent?.newDirectoryStream()?.use { it.map(::PathLyricsFile) }
    override fun newInputStream(): InputStream = path.newInputStream()
    override fun toLocalFile(): File? = if (path.isLinuxPath) path.toFile() else null
}

/**
 * Finds a song's lyrics: a `.lrc` next to it (same name, any case), then synced lyrics in the file (ID3 SYLT, Vorbis
 * SYNCEDLYRICS), then the file's lyrics field (ID3 USLT, Vorbis LYRICS, MP4 ©lyr), which may itself be LRC.
 *
 * Works on every source Ordo opens: local storage and SD cards, and folders Ordo can list (SAF trees, SMB, SFTP)
 * also get the `.lrc` lookup. A content:// song from another app has no folder, so only its embedded lyrics count.
 * Songs not on local storage are copied to a temporary file (up to [MAX_COPY_BYTES]) for the tag reader.
 *
 * Results, also "none", are cached per song until the song or its `.lrc` changes, appears or disappears.
 * Never writes to any file.
 */
object LyricsRepository {
    const val MAX_BYTES = 512 * 1024
    const val MAX_COPY_BYTES = 64L * 1024 * 1024
    private const val CACHE_SIZE = 32

    private class Entry(val stamp: String, val lyrics: Lyrics?)

    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > CACHE_SIZE
    }

    suspend fun load(song: MusicSong): Lyrics? = load(Paths.get(song.path))

    /** [uri] is what the player has as `MediaItem.mediaId`: a file://, content:// or other Ordo path URI. */
    suspend fun load(uri: String): Lyrics? =
        withContext(Dispatchers.IO) { runCatching { Paths.get(URI.create(uri)) }.getOrNull()?.let(::loadBlocking) }

    suspend fun load(path: Path): Lyrics? = withContext(Dispatchers.IO) { loadBlocking(path) }

    private fun loadBlocking(path: Path): Lyrics? = loadBlocking(PathLyricsFile(path))

    internal fun loadBlocking(file: LyricsFile): Lyrics? {
        val sidecar = runCatching { findSidecar(file) }.getOrNull()
        val stamp = runCatching {
            "${file.lastModified}/${file.size}|" +
                (sidecar?.let { "${it.key}/${it.lastModified}/${it.size}" } ?: "absent")
        }.getOrElse { return null }
        synchronized(cache) { cache[file.key]?.takeIf { it.stamp == stamp }?.let { return it.lyrics } }
        val sidecarText = sidecar?.takeIf { it.size <= MAX_BYTES }
            ?.let { runCatching { decodeText(it.newInputStream().use(InputStream::readBytes)) }.getOrNull() }
        val lyrics = pick(sidecarText) { runCatching { readEmbedded(file) }.getOrDefault(EmbeddedLyrics()) }
        synchronized(cache) { cache[file.key] = Entry(stamp, lyrics) }
        return lyrics
    }

    private fun findSidecar(file: LyricsFile): LyricsFile? {
        val name = file.name.substringBeforeLast('.') + ".lrc"
        val siblings = file.siblings() ?: return null
        return siblings.firstOrNull { it.name == name } ?: siblings.firstOrNull { it.name.equals(name, true) }
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

    private fun readEmbedded(file: LyricsFile): EmbeddedLyrics {
        // Also sets up jaudiotagger for Android (SongTagIo's init). jaudiotagger picks its reader by the extension.
        val extension = file.name.substringAfterLast('.', "")
        if (!SongTagIo.isEditableFormat(File("song.$extension"))) return EmbeddedLyrics()
        file.toLocalFile()?.let { return readEmbedded(it) }
        if (file.size > MAX_COPY_BYTES) return EmbeddedLyrics()
        val copy = File.createTempFile("ordo-lyrics-", ".$extension")
        try {
            file.newInputStream().use { input -> copy.outputStream().use { input.copyTo(it) } }
            return readEmbedded(copy)
        } finally {
            copy.delete()
        }
    }

    private fun readEmbedded(file: File): EmbeddedLyrics {
        val audio = AudioFileIO.read(file)
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
