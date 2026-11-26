/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.id3.valuepair.ImageFormats
import org.jaudiotagger.tag.images.ArtworkFactory
import org.jaudiotagger.tag.reference.ID3V2Version
import org.jaudiotagger.tag.reference.PictureTypes

/** The fields Ordo Music edits, in the order the editor shows them. */
enum class TagField(internal val key: FieldKey, val numeric: Boolean = false) {
    TITLE(FieldKey.TITLE),
    ARTIST(FieldKey.ARTIST),
    ALBUM(FieldKey.ALBUM),
    ALBUM_ARTIST(FieldKey.ALBUM_ARTIST),
    TRACK(FieldKey.TRACK, true),
    TRACK_TOTAL(FieldKey.TRACK_TOTAL, true),
    DISC(FieldKey.DISC_NO, true),
    YEAR(FieldKey.YEAR),
    GENRE(FieldKey.GENRE),
    LYRICS(FieldKey.LYRICS)
}

class Cover(val data: ByteArray, val mimeType: String)

/** What a file says: every field (empty when missing) and its front cover, if any. */
data class SongTags(val fields: Map<TagField, String>, val cover: Cover?) {
    operator fun get(field: TagField): String = fields[field].orEmpty()
}

sealed class CoverChange {
    object Keep : CoverChange()
    object Remove : CoverChange()
    class Set(val cover: Cover) : CoverChange()
}

/** Only what the user changed: a field with "" is cleared, a field not in the map stays as it is in each file. */
class TagChanges(val fields: Map<TagField, String>, val cover: CoverChange = CoverChange.Keep) {
    val isEmpty: Boolean
        get() = fields.isEmpty() && cover is CoverChange.Keep
}

enum class TagProblem { YEAR, NUMBER, TRACK_ABOVE_TOTAL }

/**
 * Reads and writes tags with jaudiotagger (LGPL-2.1+): ID3v2 in MP3, Vorbis comments in FLAC and Ogg Vorbis, MP4
 * atoms in M4A. A save never touches the original until a copy with the new tags has been written and read back:
 * the copy then replaces the original by a rename in the same folder, and the original's bytes are kept as a backup
 * for undo.
 */
object SongTagIo {
    init {
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
        TagOptionSingleton.getInstance().apply {
            // AWT/ImageIO do not exist on Android; covers are passed as bytes.
            setAndroid(System.getProperty("java.vm.vendor").orEmpty().contains("Android", true))
            // ID3v2.3 is what every player and Android's media scanner read.
            setID3V2Version(ID3V2Version.ID3_V23)
        }
    }

    fun isEditableFormat(file: File): Boolean = TagEditRules.isEditableFormat(file.extension)

    fun read(file: File): SongTags {
        val tag = AudioFileIO.read(file).tag
        val fields = TagField.entries.associateWith { field ->
            tag?.let { runCatching { it.getFirst(field.key) }.getOrNull() }.orEmpty().trim()
        }
        val artwork = tag?.firstArtwork
        val cover = artwork?.binaryData?.takeIf { it.isNotEmpty() }
            ?.let { Cover(it, artwork.mimeType?.takeIf(String::isNotBlank) ?: ImageFormats.getMimeTypeForBinarySignature(it)) }
        return SongTags(fields, cover)
    }

    /**
     * Writes [changes] into [file]. Returns the backup of the original (in [backupDir]) for undo. On any failure the
     * original is untouched and the exception is thrown. [beforeReplace] is for tests that simulate a failure.
     */
    fun write(file: File, changes: TagChanges, backupDir: File, beforeReplace: (File) -> Unit = {}): File {
        val temp = File(file.parentFile, ".ordo-edit-${UUID.randomUUID()}.${file.extension}")
        file.copyTo(temp)
        try {
            val audio = AudioFileIO.read(temp)
            val tag = audio.tagOrCreateAndSetDefault
            for ((field, value) in changes.fields) {
                if (value.isBlank()) tag.deleteField(field.key) else tag.setField(field.key, value.trim())
            }
            when (val cover = changes.cover) {
                CoverChange.Keep -> {}
                CoverChange.Remove -> tag.deleteArtworkField()
                is CoverChange.Set -> {
                    tag.deleteArtworkField()
                    tag.setField(ArtworkFactory.getNew().apply {
                        binaryData = cover.cover.data
                        mimeType = cover.cover.mimeType
                        pictureType = PictureTypes.DEFAULT_ID
                    })
                }
            }
            audio.commit()
            verify(read(temp), changes)
            beforeReplace(temp)
            backupDir.mkdirs()
            val backup = File(backupDir, "${UUID.randomUUID()}.${file.extension}")
            file.copyTo(backup)
            try {
                replace(file, temp)
            } catch (e: ReplaceException) {
                // Only when the original is back in place: otherwise the backup is the safe copy and stays.
                if (e.originalInPlace) backup.delete()
                throw e
            }
            return backup
        } finally {
            temp.delete()
        }
    }

    /** Puts the original back (undo), the same way: a copy next to it, then a rename. */
    fun restore(file: File, backup: File) {
        val temp = File(file.parentFile, ".ordo-edit-${UUID.randomUUID()}.${file.extension}")
        try {
            backup.copyTo(temp)
            replace(file, temp)
        } finally {
            temp.delete()
        }
    }

    /**
     * Puts [temp] in [file]'s place. The original is renamed aside first, never overwritten by a rename: on Android's
     * media storage a rename over an existing song left MediaStore's row (title, date) as it was, and no later scan
     * read the new tags (Galaxy A15, Android 16, rc1d). Moved aside, the new file gets its own row and the scan fills it.
     */
    private fun replace(file: File, temp: File) {
        val old = File(file.parentFile, ".ordo-old-${UUID.randomUUID()}.${file.extension}")
        if (!file.renameTo(old)) throw ReplaceException("Could not replace $file", originalInPlace = true)
        if (!temp.renameTo(file)) {
            // Back to its path: by rename, else by copy; only if both fail it stays next to it (.ordo-old-*), and the
            // message names it.
            val back = old.renameTo(file) ||
                runCatching { old.copyTo(file, overwrite = true) }.onSuccess { old.delete() }.isSuccess
            if (!back) {
                throw ReplaceException("Could not replace $file; the original is kept as $old", originalInPlace = false)
            }
            throw ReplaceException("Could not replace $file", originalInPlace = true)
        }
        old.delete()
    }

    private class ReplaceException(message: String, val originalInPlace: Boolean) : IOException(message)

    private fun verify(written: SongTags, changes: TagChanges) {
        for ((field, value) in changes.fields) {
            val expected = value.trim()
            val actual = written[field]
            val same = if (field.numeric && expected.isNotEmpty()) {
                expected.toIntOrNull() == actual.toIntOrNull()
            } else {
                expected == actual
            }
            if (!same) throw IOException("$field reads back as \"$actual\", not \"$expected\"")
        }
        when (val cover = changes.cover) {
            CoverChange.Keep -> {}
            CoverChange.Remove -> if (written.cover != null) throw IOException("The cover is still there")
            is CoverChange.Set -> if (written.cover?.data?.contentEquals(cover.cover.data) != true) {
                throw IOException("The cover reads back differently")
            }
        }
    }

    /** One value per field for several files: the common value, or null where they differ ("Several values"). */
    fun merge(tags: List<SongTags>): Map<TagField, String?> =
        TagField.entries.associateWith { field -> tags.map { it[field] }.distinct().singleOrNull() }

    /** The changes between what the editor started with ([original], null = differing) and what it holds now. */
    fun changes(original: Map<TagField, String?>, edited: Map<TagField, String>, cover: CoverChange): TagChanges =
        TagChanges(
            edited.filter { (field, value) ->
                val before = original[field]
                // A field with several values only changes when the user typed a new one.
                if (before == null) value.isNotBlank() else value.trim() != before
            }.mapValues { it.value.trim() },
            cover
        )

    fun validate(fields: Map<TagField, String>): Map<TagField, TagProblem> {
        val problems = mutableMapOf<TagField, TagProblem>()
        fields[TagField.YEAR]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (!Regex("\\d{4}").matches(it)) problems[TagField.YEAR] = TagProblem.YEAR
        }
        for (field in listOf(TagField.TRACK, TagField.TRACK_TOTAL, TagField.DISC)) {
            val value = fields[field]?.trim().orEmpty()
            if (value.isNotEmpty() && (value.toIntOrNull() ?: 0) !in 1..999) problems[field] = TagProblem.NUMBER
        }
        val track = fields[TagField.TRACK]?.trim()?.toIntOrNull()
        val total = fields[TagField.TRACK_TOTAL]?.trim()?.toIntOrNull()
        if (track != null && total != null && track > total && TagField.TRACK !in problems) {
            problems[TagField.TRACK] = TagProblem.TRACK_ABOVE_TOTAL
        }
        return problems
    }
}
