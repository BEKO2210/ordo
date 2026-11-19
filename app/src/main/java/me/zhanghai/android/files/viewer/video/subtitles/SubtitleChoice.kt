/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.subtitles

/**
 * Which subtitles show, as remembered per video: none, a subtitle file (by its path URI) or a track of the film (by its
 * Media3 format ID, with the language as a fallback when the ID changed). Pure, unit-tested.
 */
sealed class SubtitleChoice {
    object Off : SubtitleChoice()

    data class File(val uri: String) : SubtitleChoice()

    data class Track(val id: String?, val language: String?) : SubtitleChoice()

    fun encode(): String = when (this) {
        Off -> OFF
        is File -> FILE + uri
        is Track -> TRACK + (id ?: "") + SEPARATOR + (language ?: "")
    }

    /** A subtitle the player can offer right now: a file next to the film (or loaded) or a track inside it. */
    data class Candidate(val choice: SubtitleChoice, val language: String?, val forced: Boolean = false)

    companion object {
        private const val OFF = "off"
        private const val FILE = "file:"
        private const val TRACK = "track:"
        private const val SEPARATOR = '\u001f'

        fun decode(value: String?): SubtitleChoice? = when {
            value == null -> null
            value == OFF -> Off
            value.startsWith(FILE) -> value.removePrefix(FILE).takeIf { it.isNotEmpty() }?.let(::File)
            value.startsWith(TRACK) -> value.removePrefix(TRACK).let { rest ->
                if (SEPARATOR !in rest) return null
                Track(rest.substringBefore(SEPARATOR).ifEmpty { null }, rest.substringAfter(SEPARATOR).ifEmpty { null })
            }
            else -> null
        }

        /**
         * What shows when a film starts; null leaves the player's own pick (only forced tracks, see VideoSubtitles).
         * 1. The choice remembered for this film, when it is still there (a track by ID, else by language).
         * 2. With "subtitles automatically" on and the sound not already in [preferred]: a file in that language,
         *    a track in it, then a file without a language tag (named after the film, so meant for it).
         */
        fun pick(
            remembered: SubtitleChoice?,
            auto: Boolean,
            preferred: String,
            audioLanguage: String?,
            files: List<Candidate>,
            tracks: List<Candidate>
        ): SubtitleChoice? {
            when (remembered) {
                null -> {}
                Off -> return Off
                is File -> files.firstOrNull { it.choice == remembered }?.let { return it.choice }
                is Track -> {
                    val all = tracks.map { it.choice as Track }
                    (all.firstOrNull { remembered.id != null && it.id == remembered.id }
                        ?: all.firstOrNull { remembered.language != null && sameLanguage(it.language, remembered.language) })
                        ?.let { return it }
                }
            }
            if (!auto || sameLanguage(audioLanguage, preferred)) return null
            return (files.firstOrNull { !it.forced && sameLanguage(it.language, preferred) }
                ?: tracks.firstOrNull { !it.forced && sameLanguage(it.language, preferred) }
                ?: files.firstOrNull { it.language == null })?.choice
        }

        /** "de", "de-DE", "deu" and "ger" count as one language. */
        fun sameLanguage(a: String?, b: String?): Boolean {
            if (a.isNullOrEmpty() || b.isNullOrEmpty()) return false
            return base(a).equals(base(b), ignoreCase = true)
        }

        private fun base(tag: String): String =
            tag.substringBefore('-').substringBefore('_').let { SubtitleFiles.languageCode(it) ?: it }
    }
}
