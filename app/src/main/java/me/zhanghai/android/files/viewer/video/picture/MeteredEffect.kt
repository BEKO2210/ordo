/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.annotation.OptIn
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/** [effect] with the GPU time of each of its frames added to [meter] under [pass]. */
@OptIn(UnstableApi::class)
internal data class MeteredEffect(val effect: GlEffect, val pass: String, val meter: PictureMeter) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        MeteredProgram(effect.toGlShaderProgram(context, useHdr), pass, meter)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = effect.isNoOp(inputWidth, inputHeight)
}

/**
 * Times [inner]'s draw, which BaseGlShaderProgram does inside queueInputFrame on the GL thread. With
 * EXT_disjoint_timer_query (OpenGL ES 3 context) the GPU's own clock, read a few frames later so nothing waits;
 * without it, and only while measuring, glFinish around the draw (that stalls the pipeline, so never otherwise).
 */
@OptIn(UnstableApi::class)
private class MeteredProgram(
    private val inner: GlShaderProgram,
    private val pass: String,
    private val meter: PictureMeter
) : GlShaderProgram by inner {
    private var gpu: Boolean? = null
    private val queries = IntArray(QUERIES)
    private val pending = BooleanArray(QUERIES)
    private var next = 0

    override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
        val useGpu = gpu ?: initTimer().also { gpu = it }
        // One TIME_ELAPSED query may run at a time; a pass started from inside another is left untimed.
        if (useGpu && !timing) {
            poll()
            if (!pending[next]) {
                timing = true
                GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, queries[next])
                try {
                    inner.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs)
                } finally {
                    GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
                    timing = false
                }
                pending[next] = true
                next = (next + 1) % QUERIES
                return
            }
        } else if (!useGpu && meter.measuring && !timing) {
            meter.timer = "cpu"
            timing = true
            GLES20.glFinish()
            val start = System.nanoTime()
            try {
                inner.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs)
                GLES20.glFinish()
            } finally {
                timing = false
            }
            meter.addPass(pass, (System.nanoTime() - start) / 1e6)
            return
        }
        inner.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs)
    }

    private fun initTimer(): Boolean {
        val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
        val available = DetailPlusGl.esMajor(version) >= 3 && "GL_EXT_disjoint_timer_query" in extensions
        if (available) {
            GLES30.glGenQueries(QUERIES, queries, 0)
            meter.timer = "gpu"
        }
        return available
    }

    /** Collects every finished query in order; a disjoint (clock change, power state) voids what is pending. */
    private fun poll() {
        val value = IntArray(1)
        GLES20.glGetIntegerv(GL_GPU_DISJOINT_EXT, value, 0)
        val disjoint = value[0] != 0
        for (offset in 0 until QUERIES) {
            val index = (next + offset) % QUERIES
            if (!pending[index]) continue
            GLES30.glGetQueryObjectuiv(queries[index], GLES30.GL_QUERY_RESULT_AVAILABLE, value, 0)
            if (value[0] == 0) break
            GLES30.glGetQueryObjectuiv(queries[index], GLES30.GL_QUERY_RESULT, value, 0)
            pending[index] = false
            // Unsigned nanoseconds: 4.29 s at most, far beyond a frame.
            if (!disjoint) meter.addPass(pass, (value[0].toLong() and 0xffffffffL) / 1e6)
        }
    }

    override fun release() {
        try {
            if (gpu == true) GLES30.glDeleteQueries(QUERIES, queries, 0)
        } finally {
            inner.release()
        }
    }

    companion object {
        private const val QUERIES = 4
        private const val GL_TIME_ELAPSED_EXT = 0x88BF
        private const val GL_GPU_DISJOINT_EXT = 0x8FBB

        // Only touched on Media3's one GL thread.
        private var timing = false
    }
}
