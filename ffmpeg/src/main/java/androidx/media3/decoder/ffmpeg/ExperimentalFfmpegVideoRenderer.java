/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Replaces Media3 1.11.1's placeholder of the same name (its supportsFormat always said "unsupported", PR 1591 is still
 * open). The body is nextlib's FfmpegVideoRenderer (anilbeesetti/nextlib, GPL-3.0, tag v1.11.1-0.18.0, commit
 * d774e7e6d65204d409dc679ab173c67030d4a7d4), renamed, so DefaultRenderersFactory finds it by reflection under
 * EXTENSION_RENDERER_MODE_ON: after the platform's video renderer, used only for what no platform decoder takes.
 */
package androidx.media3.decoder.ffmpeg;

import static java.lang.Runtime.getRuntime;

import android.os.Handler;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.video.DecoderVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

/** Software video decoding with FFmpeg (H.264, HEVC incl. 10-bit, MPEG-2, MPEG-4 ASP/Xvid/DivX, H.263, MS-MPEG-4, VC-1). */
@UnstableApi
public final class ExperimentalFfmpegVideoRenderer extends DecoderVideoRenderer {

    private static final String TAG = "FfmpegVideoRenderer";

    private static final int DEFAULT_NUM_OF_INPUT_BUFFERS = 4;
    private static final int DEFAULT_NUM_OF_OUTPUT_BUFFERS = 4;
    /* Default size based on 720p resolution video compressed by a factor of two. */
    private static final int DEFAULT_INPUT_BUFFER_SIZE =
            Util.ceilDivide(1280, 64) * Util.ceilDivide(720, 64) * (64 * 64 * 3 / 2) / 2;

    private final int threads;

    @Nullable private FfmpegVideoDecoder decoder;

    /** The constructor DefaultRenderersFactory looks up by reflection (kept by Media3's own ProGuard rules). */
    public ExperimentalFfmpegVideoRenderer(
            long allowedJoiningTimeMs,
            @Nullable Handler eventHandler,
            @Nullable VideoRendererEventListener eventListener,
            int maxDroppedFramesToNotify) {
        super(allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify);
        this.threads = getRuntime().availableProcessors();
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    @RendererCapabilities.Capabilities
    public final int supportsFormat(Format format) {
        String mimeType = format.sampleMimeType;
        if (!MimeTypes.isVideo(mimeType) || FfmpegVideoDecoder.getCodecName(mimeType) == null) {
            return C.FORMAT_UNSUPPORTED_TYPE;
        } else if (!FfmpegVideoDecoder.supportsMimeType(mimeType)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE);
        } else if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM);
        } else {
            return RendererCapabilities.create(
                    C.FORMAT_HANDLED,
                    ADAPTIVE_NOT_SEAMLESS,
                    TUNNELING_NOT_SUPPORTED);
        }
    }

    @Override
    protected void renderOutputBufferToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
            throws FfmpegDecoderException {
        try {
            if (decoder == null) {
                throw new FfmpegDecoderException(
                        "Failed to render output buffer to surface: decoder is not initialized.");
            }
            decoder.renderToSurface(outputBuffer, surface);
        } finally {
            outputBuffer.release();
        }
    }

    @Override
    protected Decoder<DecoderInputBuffer, ? extends VideoDecoderOutputBuffer, ? extends DecoderException> createDecoder(
            Format format, @Nullable CryptoConfig cryptoConfig) throws DecoderException {
        TraceUtil.beginSection("createFfmpegVideoDecoder");
        int initialInputBufferSize = format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
        FfmpegVideoDecoder decoder = new FfmpegVideoDecoder(
                DEFAULT_NUM_OF_INPUT_BUFFERS, DEFAULT_NUM_OF_OUTPUT_BUFFERS, initialInputBufferSize, threads, format);
        this.decoder = decoder;
        TraceUtil.endSection();
        return decoder;
    }

    @Override
    protected void setDecoderOutputMode(@C.VideoOutputMode int outputMode) {
        if (decoder != null) {
            decoder.setOutputMode(outputMode);
        }
    }
}
