/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DebugViewProvider
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.VideoGraph
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.SingleInputVideoGraph
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * What the tone mapping measured, for the signal path (written on the GL thread, read on the main thread).
 */
internal class HdrLive {
    /** The smoothed peak of the current scene in cd/m², NaN before the first measurement. */
    @Volatile
    var peakNits = Double.NaN
}

/**
 * HDR to SDR after [HdrToneMap] as one pass with its own peak measurement. It gets Media3's linear BT.2020 light in
 * RGBA16F (1.0 = 1000 cd/m² for PQ; scene light for HLG, whose BT.2100 OOTF at 1000 cd/m² is applied here), maps it
 * onto [targetNits], converts to BT.709 and gamma 2.2, and hands Media3's final PQ encoder the light whose PQ code is
 * that SDR code: [HdrGraph] makes the screen surface an ordinary SDR one, so those codes are shown as they are.
 */
@OptIn(UnstableApi::class)
internal data class HdrToneMapEffect(
    val hlg: Boolean,
    val targetNits: Double,
    val live: HdrLive,
    /** The screen area: a larger video is written at this size (the final copy would scale it down anyway). */
    val maxWidth: Int,
    val maxHeight: Int
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        HdrToneMapProgram(hlg, targetNits, live, maxWidth, maxHeight)
}

/**
 * Builds Media3's video graph. While [toneMap] is set when the graph is made (the player's renderer makes one when the
 * pipeline is engaged and keeps it), HDR is kept as linear light up to the effects (Media3 only does that for HDR
 * output) and the PQ output goes to the screen surface without a BT.2020-PQ colour space, the way Media3 writes to an
 * encoder: so the surface is SDR and SurfaceFlinger does not map it a second time.
 */
@OptIn(UnstableApi::class)
class HdrGraph : VideoGraph.Factory {
    @Volatile
    var toneMap = false
    private val inner = SingleInputVideoGraph.Factory()

    override fun create(
        context: Context,
        outputColorInfo: ColorInfo,
        debugViewProvider: DebugViewProvider,
        listener: VideoGraph.Listener,
        listenerExecutor: Executor,
        initialTimestampOffsetUs: Long,
        renderFramesAutomatically: Boolean
    ): VideoGraph {
        if (!toneMap) {
            return inner.create(
                context, outputColorInfo, debugViewProvider, listener, listenerExecutor, initialTimestampOffsetUs,
                renderFramesAutomatically
            )
        }
        val graph = inner.create(
            context, PQ_OUTPUT, debugViewProvider, listener, listenerExecutor, initialTimestampOffsetUs,
            renderFramesAutomatically
        )
        return object : VideoGraph by graph {
            override fun setOutputSurfaceInfo(outputSurfaceInfo: SurfaceInfo?) = graph.setOutputSurfaceInfo(
                outputSurfaceInfo?.run { SurfaceInfo(surface, width, height, orientationDegrees, true) }
            )
        }
    }

    override fun supportsMultipleInputs(): Boolean = false

    companion object {
        private val PQ_OUTPUT = ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorRange(C.COLOR_RANGE_LIMITED)
            .setColorTransfer(C.COLOR_TRANSFER_ST2084)
            .build()
    }
}

@OptIn(UnstableApi::class)
private class HdrToneMapProgram(
    hlg: Boolean,
    private val targetNits: Double,
    private val live: HdrLive,
    private val maxWidth: Int,
    private val maxHeight: Int
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ true, /* texturePoolCapacity= */ 1) {
    private val main: GlProgram
    private val measure: GlProgram
    private val measureTexture: Int
    private val measureFbo: Int
    private val outTexture = table(OUT_SIZE, HdrToneMap.outputTable(OUT_SIZE))
    private val curveTexture = table(CURVE_SIZE, FloatArray(CURVE_SIZE))
    private var curvePeak = Double.NaN
    private val pbos = IntArray(SLOTS)
    private val fences = LongArray(SLOTS)
    private var slot = 0
    private var frames = 0
    private var measurements = 0
    private var tracker = HdrToneMap.SceneTracker()
    private val blockPeaks = DoubleArray(MEASURE_W * MEASURE_H)

    init {
        try {
            main = GlProgram(HdrToneMapShader.VERTEX, HdrToneMapShader.MAIN)
            measure = GlProgram(HdrToneMapShader.VERTEX, HdrToneMapShader.MEASURE)
            measureTexture = GlUtil.createTexture(MEASURE_W, MEASURE_H, /* useHighPrecisionColorComponents= */ false)
            measureFbo = GlUtil.createFboForTexture(measureTexture)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        for (program in listOf(main, measure)) {
            program.setBufferAttribute(
                "aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
            program.setFloatUniform("uHlg", if (hlg) 1f else 0f)
        }
        main.setFloatUniform("uTarget", targetNits.toFloat())
        measure.setFloatsUniform("uGrid", floatArrayOf(MEASURE_W.toFloat(), MEASURE_H.toFloat()))
        GLES30.glGenBuffers(SLOTS, pbos, 0)
        for (pbo in pbos) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo)
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, MEASURE_BYTES, null, GLES30.GL_STREAM_READ)
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
    }

    // RGBA16F in and out makes the pass memory-bound: 4K written at 2960 × 1665 on the Tab S9 is 40 % fewer bytes.
    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val scale = minOf(1f, maxWidth.toFloat() / inputWidth, maxHeight.toFloat() / inputHeight)
        return if (scale <= 0f || scale >= 1f) Size(inputWidth, inputHeight)
        else Size(Math.round(inputWidth * scale), Math.round(inputHeight * scale))
    }

    // The main pass covers every pixel without blending: a clear would only be one more full-frame write.
    override fun shouldClearTextureBuffer(): Boolean = false

    /**
     * The frame's statistics go into the small target first, before BaseGlShaderProgram focuses the output: switching
     * framebuffers in the middle of the output's pass would make a tiling GPU store and reload the whole frame.
     * They are read back a frame or two later, so nothing waits.
     */
    override fun queueInputFrame(
        glObjectsProvider: GlObjectsProvider,
        inputTexture: GlTextureInfo,
        presentationTimeUs: Long
    ) {
        collect()
        // Scattered reads of a 4K RGBA16F frame are the expensive part (Tab S9: 1.1 ms for 16 per block, measured):
        // four per block, and above 1080p only every second frame.
        val every = if (inputTexture.width.toLong() * inputTexture.height > LARGE_PIXELS) 2 else 1
        if (frames++ % every == 0 && fences[slot] == 0L) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, measureFbo)
            GLES20.glViewport(0, 0, MEASURE_W, MEASURE_H)
            measure.use()
            measure.setSamplerTexIdUniform("uTexSampler", inputTexture.texId, 0)
            measure.setFloatsUniform("uJitter", JITTER[measurements++ % JITTER.size])
            measure.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[slot])
            GLES30.glReadPixels(0, 0, MEASURE_W, MEASURE_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            fences[slot] = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            slot = (slot + 1) % SLOTS
        }
        super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val peak = HdrToneMap.pqToNits(tracker.peak.takeUnless { it.isNaN() } ?: HdrToneMap.pq(HdrToneMap.DEFAULT_PEAK))
            main.use()
            main.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            val knee = HdrToneMap.kneeNits(peak, targetNits)
            main.setFloatUniform("uKnee", if (knee.isInfinite()) Float.MAX_VALUE else knee.toFloat())
            main.setFloatUniform("uSpan", if (knee.isInfinite()) 1f else (peak - knee).toFloat())
            // The curve above the knee changes with the measured peak only: a table, so pixels need no pow().
            if (peak != curvePeak && !knee.isInfinite()) {
                curvePeak = peak
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTexture)
                GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D, 0, 0, 0, CURVE_SIZE, 1, GLES30.GL_RED, GLES20.GL_FLOAT,
                    FloatBuffer.wrap(HdrToneMap.curveTable(peak, targetNits, CURVE_SIZE))
                )
            }
            main.setSamplerTexIdUniform("uOut", outTexture, 1)
            main.setSamplerTexIdUniform("uCurve", curveTexture, 2)
            main.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    /**
     * A table as a size × 1 R16F texture (linear filtering is core for half floats in ES 3): [HdrToneMap.outputTable]
     * replaces gamma 2.2 and the PQ curve, [HdrToneMap.curveTable] the EETF, so a pixel costs square roots and lookups
     * instead of up to thirteen pow calls (Tab S9, 4K: bright scenes cost 0.3–0.7 ms more than dark ones with pow).
     */
    private fun table(size: Int, values: FloatArray): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R16F, size, 1, 0, GLES30.GL_RED, GLES20.GL_FLOAT,
            FloatBuffer.wrap(values)
        )
        for ((name, value) in listOf(
            GLES20.GL_TEXTURE_MIN_FILTER to GLES20.GL_LINEAR, GLES20.GL_TEXTURE_MAG_FILTER to GLES20.GL_LINEAR,
            GLES20.GL_TEXTURE_WRAP_S to GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_TEXTURE_WRAP_T to GLES20.GL_CLAMP_TO_EDGE
        )) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, name, value)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    /** Every finished readback, oldest first: block maxima into the histogram, the averages into the scene check. */
    private fun collect() {
        for (offset in 0 until SLOTS) {
            val index = (slot + offset) % SLOTS
            val fence = fences[index]
            if (fence == 0L) continue
            val state = GLES30.glClientWaitSync(fence, 0, 0)
            if (state == GLES30.GL_TIMEOUT_EXPIRED) return
            GLES30.glDeleteSync(fence)
            fences[index] = 0L
            // GL_WAIT_FAILED: the slot is freed, this frame's measurement is skipped.
            if (state == GLES30.GL_WAIT_FAILED) continue
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[index])
            val bytes = GLES30.glMapBufferRange(
                GLES30.GL_PIXEL_PACK_BUFFER, 0, MEASURE_BYTES, GLES30.GL_MAP_READ_BIT
            ) as ByteBuffer?
            if (bytes != null) {
                var sum = 0.0
                for (i in blockPeaks.indices) {
                    val p = i * 4
                    blockPeaks[i] = bytes.unpack(p)
                    sum += bytes.unpack(p + 2)
                }
                GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
                val peak = tracker.update(HdrToneMap.framePeak(blockPeaks), sum / blockPeaks.size)
                live.peakNits = HdrToneMap.pqToNits(peak)
            }
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        }
    }

    private fun ByteBuffer.unpack(index: Int): Double =
        ((get(index).toInt() and 0xff) * 256 + (get(index + 1).toInt() and 0xff)) / 65535.0

    /** A seek: readbacks still in flight belong to the old position, and the new one starts its own scene. */
    override fun flush() {
        super.flush()
        for (i in fences.indices) {
            if (fences[i] != 0L) GLES30.glDeleteSync(fences[i])
            fences[i] = 0L
        }
        tracker = HdrToneMap.SceneTracker()
        live.peakNits = Double.NaN
    }

    override fun release() {
        super.release()
        try {
            for (fence in fences) if (fence != 0L) GLES30.glDeleteSync(fence)
            GLES30.glDeleteBuffers(SLOTS, pbos, 0)
            GlUtil.deleteFbo(measureFbo)
            GlUtil.deleteTexture(measureTexture)
            GlUtil.deleteTexture(outTexture)
            GlUtil.deleteTexture(curveTexture)
            main.delete()
            measure.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        /** The downscaled frame: 128 × 72 blocks, each the maximum and mean of 2 × 2 of 4 × 4 samples. */
        const val MEASURE_W = 128
        const val MEASURE_H = 72
        private const val MEASURE_BYTES = MEASURE_W * MEASURE_H * 4
        private const val SLOTS = 3
        private const val LARGE_PIXELS = 2_500_000L
        private val JITTER = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f), floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))
        private const val OUT_SIZE = 1024
        private const val CURVE_SIZE = 256
    }
}

/** GLSL for [HdrToneMapProgram]; the formulas are [HdrToneMap]'s. */
internal object HdrToneMapShader {
    const val VERTEX = """#version 300 es
in vec4 aFramePosition;
out vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}"""

    private const val COMMON = """#version 300 es
precision highp float;
uniform sampler2D uTexSampler;
uniform float uHlg;
in vec2 vTexSamplingCoord;
out vec4 outColor;
const vec3 Y2020 = vec3(0.2627, 0.6780, 0.0593);
const float M1 = 2610.0 / 16384.0;
const float M2 = 2523.0 / 4096.0 * 128.0;
const float C1 = 3424.0 / 4096.0;
const float C2 = 2413.0 / 4096.0 * 32.0;
const float C3 = 2392.0 / 4096.0 * 32.0;
float pqEncode(float nits) {
  float y = pow(clamp(nits / 10000.0, 0.0, 1.0), M1);
  return pow((C1 + C2 * y) / (1.0 + C3 * y), M2);
}
// Media3's linear light to cd/m²: PQ is 1.0 = 1000; HLG is scene light, BT.2100 OOTF at 1000 cd/m² (gamma 1.2).
vec3 nits(vec3 rgb) {
  rgb = max(rgb, 0.0);
  if (uHlg > 0.5) return rgb * 1000.0 * pow(max(dot(rgb, Y2020), 1e-6), 0.2);
  return rgb * 1000.0;
}
"""

    const val MEASURE = COMMON + """
uniform vec2 uGrid;
uniform vec2 uJitter;
vec2 pack16(float v) {
  float x = floor(clamp(v, 0.0, 1.0) * 65535.0 + 0.5);
  float hi = floor(x / 256.0);
  return vec2(hi, x - hi * 256.0) / 255.0;
}
void main() {
  vec2 cell = 1.0 / uGrid;
  vec2 base = floor(gl_FragCoord.xy) * cell;
  float peak = 0.0;
  float sum = 0.0;
  // 2 x 2 of the block's 4 x 4 grid per frame, shifted by uJitter so four frames visit all sixteen.
  for (int i = 0; i < 2; i++) {
    for (int j = 0; j < 2; j++) {
      vec3 c = nits(texture(uTexSampler, base + (vec2(2 * i, 2 * j) + uJitter + 0.5) * cell * 0.25).rgb);
      peak = max(peak, max(c.r, max(c.g, c.b)));
      sum += dot(c, Y2020);
    }
  }
  // The block's mean light, then PQ: one curve per block instead of four.
  outColor = vec4(pack16(pqEncode(peak)), pack16(pqEncode(sum / 4.0)));
}"""

    const val MAIN = COMMON + """
uniform float uTarget;
// Below the knee the curve is 1:1 (cd/m²; above any value when the scene fits the screen). Above it uCurve holds
// HdrToneMap.eetf over the target, indexed by sqrt((m - knee) / span) with span = measured peak - knee.
uniform float uKnee;
uniform float uSpan;
uniform sampler2D uCurve;
const float CURVE_SIZE = 256.0;
// Gamma 2.2 and the inverse of Media3's final PQ step in one table, indexed by sqrt of the SDR light.
uniform sampler2D uOut;
const float OUT_SIZE = 1024.0;
// BT.2020 to BT.709 primaries (BT.2087), columns.
const mat3 TO_709 = mat3(1.660491, -0.124550, -0.018151, -0.587641, 1.132900, -0.100579, -0.072850, -0.008349, 1.118730);
void main() {
  vec3 c = nits(texture(uTexSampler, vTexSamplingCoord).rgb);
  float m = max(c.r, max(c.g, c.b));
  if (m > uKnee) {
    float x = sqrt(min((m - uKnee) / uSpan, 1.0));
    c *= texture(uCurve, vec2((x * (CURVE_SIZE - 1.0) + 0.5) / CURVE_SIZE, 0.5)).r * uTarget / m;
  }
  vec3 u = (sqrt(clamp(TO_709 * c / uTarget, 0.0, 1.0)) * (OUT_SIZE - 1.0) + 0.5) / OUT_SIZE;
  outColor = vec4(texture(uOut, vec2(u.r, 0.5)).r, texture(uOut, vec2(u.g, 0.5)).r, texture(uOut, vec2(u.b, 0.5)).r, 1.0);
}"""
}
