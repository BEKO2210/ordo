/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.Tracks
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.extractor.mp3.Mp3InfoReplayGain

object ReplayGainMetadata {
    // TODO(TagLib): Media3 exposes LAME, Vorbis-comment and ID3 metadata, but not every container/tag
    // dialect carrying ReplayGain. Add TagLib rather than inventing another container parser here.
    // https://developer.android.com/media/media3/exoplayer/metadata
    fun fromTracks(tracks: Tracks): ReplayGainInfo? {
        val entries = buildList {
            tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }.forEach { group ->
                for (index in 0 until group.length) {
                    if (group.isTrackSelected(index)) group.getTrackFormat(index).metadata?.let(::add)
                }
            }
        }.flatMap { metadata -> (0 until metadata.length()).map(metadata::get) }
        return fromEntries(entries)
    }

    internal fun fromEntries(entries: List<Metadata.Entry>): ReplayGainInfo? {
        var trackGain: Float? = null
        var albumGain: Float? = null
        var trackPeak: Float? = null
        var albumPeak: Float? = null
        var r128Track: Float? = null
        var r128Album: Float? = null

        entries.forEach { entry ->
            when (entry) {
                is Mp3InfoReplayGain -> {
                    // Media3 exposes the LAME header's track (radio), album (audiophile), and peak fields.
                    // https://developer.android.com/reference/androidx/media3/extractor/mp3/Mp3InfoReplayGain
                    val fields = listOfNotNull(entry.field1, entry.field2)
                    trackGain = fields.firstOrNull {
                        it.name == Mp3InfoReplayGain.GainField.NAME_RADIO
                    }?.gain ?: trackGain
                    albumGain = fields.firstOrNull {
                        it.name == Mp3InfoReplayGain.GainField.NAME_AUDIOPHILE
                    }?.gain ?: albumGain
                    if (entry.peak > 0f) trackPeak = entry.peak
                }
                is VorbisComment -> applyTag(
                    entry.key, entry.value,
                    track = { trackGain = it }, album = { albumGain = it },
                    trackPeakValue = { trackPeak = it }, albumPeakValue = { albumPeak = it },
                    r128TrackValue = { r128Track = it }, r128AlbumValue = { r128Album = it }
                )
                is TextInformationFrame -> if (entry.id == "TXXX" && entry.values.isNotEmpty()) {
                    applyTag(
                        entry.description.orEmpty(), entry.values.first(),
                        track = { trackGain = it }, album = { albumGain = it },
                        trackPeakValue = { trackPeak = it }, albumPeakValue = { albumPeak = it },
                        r128TrackValue = { r128Track = it }, r128AlbumValue = { r128Album = it }
                    )
                }
            }
        }
        // R128 tags normalise to -23 LUFS, ReplayGain to -18 LUFS: shifted onto the ReplayGain level, an Opus file
        // with R128 tags plays as loud as an MP3 or FLAC with ReplayGain tags instead of 5 dB quieter.
        val gain = trackGain ?: r128Track?.plus(ReplayGain.R128_TO_REPLAYGAIN_DB) ?: albumGain
            ?: r128Album?.plus(ReplayGain.R128_TO_REPLAYGAIN_DB) ?: return null
        return ReplayGainInfo(gain, trackPeak ?: albumPeak)
    }

    private fun applyTag(
        rawKey: String,
        value: String,
        track: (Float) -> Unit,
        album: (Float) -> Unit,
        trackPeakValue: (Float) -> Unit,
        albumPeakValue: (Float) -> Unit,
        r128TrackValue: (Float) -> Unit,
        r128AlbumValue: (Float) -> Unit
    ) {
        when (rawKey.uppercase()) {
            "REPLAYGAIN_TRACK_GAIN" -> ReplayGain.parseDb(value)?.let(track)
            "REPLAYGAIN_ALBUM_GAIN" -> ReplayGain.parseDb(value)?.let(album)
            "REPLAYGAIN_TRACK_PEAK" -> ReplayGain.parsePeak(value)?.let(trackPeakValue)
            "REPLAYGAIN_ALBUM_PEAK" -> ReplayGain.parsePeak(value)?.let(albumPeakValue)
            "R128_TRACK_GAIN" -> ReplayGain.parseR128(value)?.let(r128TrackValue)
            "R128_ALBUM_GAIN" -> ReplayGain.parseR128(value)?.let(r128AlbumValue)
        }
    }
}
