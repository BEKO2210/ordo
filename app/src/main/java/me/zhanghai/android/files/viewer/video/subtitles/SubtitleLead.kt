/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.subtitles

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Subtitles of a track inside the film that should run early: Media3's text renderer only hands over a cue at its
 * time, so it is asked [leadUs] ahead of the film and the cues wait in [EmbeddedCueQueue] for their shifted time.
 * Media3 buffers the film well ahead, so the text samples are there; beyond its buffer an early line comes late.
 * Written on the main thread, read on the playback thread.
 */
@OptIn(UnstableApi::class)
class SubtitleLead {
    @Volatile
    var leadUs = 0L

    fun wrap(factory: RenderersFactory): RenderersFactory = object : RenderersFactory {
        override fun createRenderers(
            eventHandler: Handler,
            videoRendererEventListener: VideoRendererEventListener,
            audioRendererEventListener: AudioRendererEventListener,
            textRendererOutput: TextOutput,
            metadataRendererOutput: MetadataOutput
        ): Array<Renderer> = factory.createRenderers(
            eventHandler, videoRendererEventListener, audioRendererEventListener, textRendererOutput,
            metadataRendererOutput
        ).map { if (it.trackType == C.TRACK_TYPE_TEXT) Ahead(it) else it }.toTypedArray()

        override fun createSecondaryRenderer(
            renderer: Renderer,
            eventHandler: Handler,
            videoRendererEventListener: VideoRendererEventListener,
            audioRendererEventListener: AudioRendererEventListener,
            textRendererOutput: TextOutput,
            metadataRendererOutput: MetadataOutput
        ): Renderer? = factory.createSecondaryRenderer(
            renderer, eventHandler, videoRendererEventListener, audioRendererEventListener, textRendererOutput,
            metadataRendererOutput
        )
    }

    private inner class Ahead(renderer: Renderer) : ForwardingRenderer(renderer) {
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) =
            super.render(positionUs + leadUs, elapsedRealtimeUs)

        override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long =
            super.getDurationToProgressUs(positionUs + leadUs, elapsedRealtimeUs)
    }
}
