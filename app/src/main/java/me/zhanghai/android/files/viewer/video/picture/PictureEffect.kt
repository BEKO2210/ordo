/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import android.content.Context
import android.opengl.GLES20
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.util.Locale

/**
 * Debanding, CAS sharpening, grain and the final dither as ONE Media3 effect after LanczosResample: the SDR effect
 * chain stores every pass as 8-bit RGBA (DefaultVideoFrameProcessor), so anything smoothed in one pass and stored would
 * band again before the next. Equal parameters make equal effects, so an unchanged list is never set twice.
 */
@OptIn(UnstableApi::class)
internal data class PictureEnhanceEffect(val params: PicturePolicy.ShaderParams, val live: PictureLive) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        PictureShaderProgram(params, live)
}

/**
 * Values that change while the video plays and must not rebuild the pipeline (setVideoEffects would): read by the
 * GL thread every frame. One instance per player screen, so equal effects stay equal.
 */
internal class PictureLive {
    @Volatile
    var gammaRatio = 1f
}

/**
 * The pass itself. Debug: `adb shell setprop log.tag.OrdoVideoPicture DEBUG`, then open the video again; each frame
 * logs its GPU time (glFinish after the draw, so only while logging) and every 120 frames the mean and maximum.
 */
@OptIn(UnstableApi::class)
private class PictureShaderProgram(
    private val params: PicturePolicy.ShaderParams,
    private val live: PictureLive
) :
    BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1) {
    private val glProgram: GlProgram
    private val debug = Log.isLoggable(PICTURE_LOG_TAG, Log.DEBUG)
    private var frame = 0
    private var width = 0
    private var height = 0
    private var sumMs = 0.0
    private var maxMs = 0.0

    init {
        glProgram = try {
            GlProgram(PictureShader.VERTEX, PictureShader.FRAGMENT)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        // The whole normalised device space, -1 to 1, as Media3's own single-pass effects draw.
        glProgram.setBufferAttribute(
            "aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
        val identity = GlUtil.create4x4IdentityMatrix()
        glProgram.setFloatsUniform("uTransformationMatrix", identity)
        glProgram.setFloatsUniform("uTexTransformationMatrix", identity)
        glProgram.setFloatUniform("uSharpness", params.sharpness)
        glProgram.setFloatUniform("uDebandIterations", params.debandIterations.toFloat())
        glProgram.setFloatUniform("uDebandThreshold", params.debandThreshold)
        glProgram.setFloatUniform("uDebandRadius", params.debandRadius)
        glProgram.setFloatUniform("uGrain", params.grain)
        glProgram.setFloatUniform("uDitherAmplitude", params.ditherAmplitude)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth
        height = inputHeight
        glProgram.setFloatsUniform("uTexelSize", floatArrayOf(1f / inputWidth, 1f / inputHeight))
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val start = if (debug) SystemClock.elapsedRealtimeNanos() else 0L
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            // Temporal noise: the grain and dither pattern changes every frame, so the eye averages it away.
            glProgram.setFloatUniform("uFrame", (frame % NOISE_PERIOD).toFloat())
            glProgram.setFloatUniform("uGammaRatio", live.gammaRatio)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
            frame++
            if (debug) {
                GLES20.glFinish()
                logFrame((SystemClock.elapsedRealtimeNanos() - start) / 1e6)
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun logFrame(ms: Double) {
        sumMs += ms
        maxMs = maxOf(maxMs, ms)
        Log.d(PICTURE_LOG_TAG, "frame $frame ${width}x$height ${ms.twoDigits()} ms $params")
        if (frame % SUMMARY_FRAMES == 0) {
            val mean = sumMs / SUMMARY_FRAMES
            Log.d(
                PICTURE_LOG_TAG, "last $SUMMARY_FRAMES frames: mean ${mean.twoDigits()} ms, max ${maxMs.twoDigits()} ms"
            )
            sumMs = 0.0
            maxMs = 0.0
        }
    }

    // Dots in the log whatever the device's language, so scripts can read it.
    private fun Double.twoDigits(): String = String.format(Locale.ROOT, "%.2f", this)

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        private const val NOISE_PERIOD = 1024
        private const val SUMMARY_FRAMES = 120
    }
}

internal const val PICTURE_LOG_TAG = "OrdoVideoPicture"
