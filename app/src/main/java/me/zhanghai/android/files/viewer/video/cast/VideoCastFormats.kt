/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.cast

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

object VideoCastFormats {
    fun contentType(fileName: String): String? = when (extension(fileName).lowercase(Locale.ROOT)) {
        "mp4", "m4v", "mov" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        else -> null
    }

    fun extension(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot < 0 || dot == fileName.lastIndex) "" else fileName.substring(dot + 1).uppercase(Locale.ROOT)
    }

    fun srtToVtt(srt: String): String {
        val normalized = srt.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.startsWith("WEBVTT")) return normalized

        val timing = Regex(
            """^(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})(?:\s+.*)?$"""
        )
        val assTag = Regex("""\{\\[^}]*}""")
        val cues = normalized.trim('\n').split(Regex("""\n[ \t]*\n+""")).filter { it.isNotBlank() }.map { cue ->
            cue.lines().joinToString("\n") { line ->
                val match = timing.matchEntire(line.trim())
                if (match != null) {
                    val value = match.groupValues
                    "%02d:%s:%s.%s --> %02d:%s:%s.%s".format(
                        Locale.ROOT,
                        value[1].toInt(), value[2], value[3], value[4],
                        value[5].toInt(), value[6], value[7], value[8]
                    )
                } else {
                    line.replace(assTag, "")
                }
            }
        }
        return if (cues.isEmpty()) "WEBVTT\n\n" else "WEBVTT\n\n${cues.joinToString("\n\n")}\n"
    }

    fun decode(bytes: ByteArray): String {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, charset("windows-1252"))
        }
        return text.removePrefix("\uFEFF")
    }

    data class Subtitle(val fileName: String, val language: String?)

    fun sidecarSubtitles(videoName: String, siblings: List<String>, deviceLanguage: String): List<Subtitle> {
        val dot = videoName.lastIndexOf('.')
        val base = if (dot < 0) videoName else videoName.substring(0, dot)
        val pattern = Regex(
            "^${Regex.escape(base)}(?:\\.([^.]+)(?:\\.[^.]+)*)?\\.(?:srt|vtt)$",
            RegexOption.IGNORE_CASE
        )
        val preferredLanguage = normalizeLanguage(deviceLanguage)
        return siblings.mapNotNull { fileName ->
            val match = pattern.matchEntire(fileName) ?: return@mapNotNull null
            Subtitle(fileName, normalizeLanguage(match.groupValues.getOrNull(1).orEmpty()))
        }.sortedWith(
            compareBy<Subtitle> {
                when {
                    it.language == preferredLanguage -> 0
                    it.language == null -> 1
                    else -> 2
                }
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.fileName }
        ).take(8)
    }

    private fun normalizeLanguage(tag: String): String? {
        val language = tag.lowercase(Locale.ROOT)
        return when (language) {
            "ger", "deu" -> "de"
            "eng" -> "en"
            else -> language.takeIf { it.matches(Regex("[a-z]{2,3}")) }
        }
    }
}
