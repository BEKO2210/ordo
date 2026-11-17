/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import me.zhanghai.android.files.file.MimeType

private val playableAudioMimeTypes = setOf(
    "audio/mpeg", "audio/mp3", "audio/x-mpeg", "audio/mpeg3", "audio/x-mpeg-3",
    "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac", "audio/x-aac", "audio/aacp", "audio/aac-adts",
    "audio/ogg", "audio/x-ogg", "audio/vorbis", "audio/opus", "application/ogg",
    "audio/flac", "audio/x-flac",
    "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave",
    "audio/webm", "audio/x-matroska", "audio/3gpp", "audio/amr", "audio/amr-wb"
)

/** Only what ExoPlayer plays without extensions: playlists (m3u), MIDI, WMA and the like stay with other apps. */
fun isPlayableAudioMimeType(mimeType: String): Boolean =
    mimeType.substringBefore(';').trim().lowercase() in playableAudioMimeTypes

val MimeType.isPlayableAudio: Boolean
    get() = isPlayableAudioMimeType(value)
