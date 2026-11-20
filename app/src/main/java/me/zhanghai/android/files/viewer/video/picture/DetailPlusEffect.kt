/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** "Detail plus": [model]'s network doubles the picture (see [DetailPlus]). */
@OptIn(UnstableApi::class)
internal data class DetailPlusEffect(val model: DetailPlus.Model) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        val source = context.assets.open(model.asset).bufferedReader().use { it.readText() }
        return DetailPlusProgram(model, MpvHook.parse(source))
    }
}

internal object DetailPlusGl {
    /** The networks this GPU can't run (no OpenGL ES 3 with half-float render targets, or a build error). */
    @Volatile
    var unsupported: Set<DetailPlus.Model> = emptySet()

    /** "OpenGL ES 3.2 V@0615.0" -> 3; 0 if unreadable. */
    fun esMajor(version: String): Int = Regex("OpenGL ES (\\d+)").find(version)?.groupValues?.get(1)?.toInt() ?: 0
}

/**
 * Runs the mpv passes in its own RGBA16F textures (the networks' features are signed and need more than Media3's
 * 8-bit passes), then draws the result into Media3's output at twice the size: the RGB of Anime4K directly, FSRCNNX's
 * luma onto the bilinearly doubled colour. On a GPU that can't, it copies the frame unchanged and sets
 * [DetailPlusGl.unsupported], so the player switches back to Lanczos alone.
 */
@OptIn(UnstableApi::class)
private class DetailPlusProgram(private val model: DetailPlus.Model, private val passes: List<MpvHook.Pass>) :
    BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1) {
    private class Step(val program: Int, val width: Int, val height: Int, val output: Int, val inputs: List<Pair<String, Int>>)

    private val plane = if (model.luma) "LUMA" else "MAIN"
    private var programs = IntArray(0)
    private var lumaProgram = 0
    private var presentProgram = 0
    private var copyOnly = false
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0) }

    // Per configure: textures and framebuffers, and the passes with their slots (-1 = Media3's input).
    private var textures = IntArray(0)
    private var framebuffers = IntArray(0)
    private var steps = emptyList<Step>()
    private var slotSizes = ArrayList<Pair<Int, Int>>()
    private var lumaSlot = -1
    private var finalSlot = -1
    private var inputWidth = 0
    private var inputHeight = 0

    init {
        try {
            val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
            val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
            val major = DetailPlusGl.esMajor(version)
            val minor = Regex("OpenGL ES \\d+\\.(\\d+)").find(version)?.groupValues?.get(1)?.toInt() ?: 0
            check(major >= 3) { "no OpenGL ES 3: $version" }
            check(
                major > 3 || minor >= 2 || "GL_EXT_color_buffer_half_float" in extensions ||
                    "GL_EXT_color_buffer_float" in extensions
            ) { "no half-float render targets" }
            programs = IntArray(passes.size) { link(MpvHook.fragment(passes[it])) }
            if (model.luma) lumaProgram = link(LUMA_FRAGMENT)
            presentProgram = link(PRESENT_FRAGMENT)
        } catch (e: Exception) {
            Log.w(PICTURE_LOG_TAG, "Detail plus can't run here, copying instead", e)
            DetailPlusGl.unsupported += model
            copyOnly = true
            if (presentProgram == 0) presentProgram = runCatching { link(PRESENT_FRAGMENT) }.getOrDefault(0)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        deleteTextures()
        if (copyOnly) return Size(inputWidth, inputHeight)
        // Each pass writes into a free texture of its size; a texture is free again after the last pass reading it.
        val sizes = HashMap<String, Pair<Int, Int>>().apply { put(plane, inputWidth to inputHeight) }
        val lastUse = HashMap<String, Int>()
        passes.forEachIndexed { index, pass -> (pass.binds + pass.hook).forEach { lastUse[it] = index } }
        slotSizes = ArrayList()
        val free = ArrayList<Int>()
        val holder = HashMap<String, Int>()
        fun take(size: Pair<Int, Int>): Int =
            free.firstOrNull { slotSizes[it] == size }?.also { free.remove(it) } ?: slotSizes.size.also { slotSizes += size }
        lumaSlot = if (model.luma) take(inputWidth to inputHeight) else -1
        holder[plane] = lumaSlot
        val planned = passes.mapIndexed { index, pass ->
            val hooked = sizes.getValue(pass.hook)
            val size = (pass.width?.let { MpvHook.eval(it, sizes).toInt() } ?: hooked.first) to
                (pass.height?.let { MpvHook.eval(it, sizes).toInt() } ?: hooked.second)
            val output = take(size)
            val inputs = (pass.binds + pass.hook).distinct().map { it to holder.getValue(it) }
            for ((name, slot) in inputs) {
                if (slot >= 0 && lastUse[name] == index && holder.values.count { it == slot } == 1) free += slot
                if (lastUse[name] == index) holder.remove(name)
            }
            val target = MpvHook.target(pass)
            holder[target]?.let { old -> holder.remove(target); if (old >= 0 && old !in holder.values) free += old }
            holder[target] = output
            sizes[target] = size
            Step(programs[index], size.first, size.second, output, inputs)
        }
        finalSlot = holder.getValue(plane)
        textures = IntArray(slotSizes.size)
        framebuffers = IntArray(slotSizes.size)
        GLES20.glGenTextures(textures.size, textures, 0)
        GLES20.glGenFramebuffers(framebuffers.size, framebuffers, 0)
        slotSizes.forEachIndexed { slot, (width, height) ->
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[slot])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, width, height, 0, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, null
            )
            for ((name, value) in TEXTURE_PARAMETERS) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, name, value)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[slot])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textures[slot], 0
            )
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.w(PICTURE_LOG_TAG, "Detail plus framebuffer incomplete ($status), copying instead")
                DetailPlusGl.unsupported += model
                copyOnly = true
                deleteTextures()
                return Size(inputWidth, inputHeight)
            }
        }
        steps = planned
        val final = slotSizes[finalSlot]
        return Size(final.first, final.second)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val value = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, value, 0)
        val output = value[0]
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, value, 0)
        if (!copyOnly) {
            if (model.luma) {
                draw(lumaProgram, framebuffers[lumaSlot], inputWidth, inputHeight, listOf(Triple("uInput", inputTexId, null)))
            }
            for (step in steps) {
                draw(step.program, framebuffers[step.output], step.width, step.height, step.inputs.map { (name, slot) ->
                    val size = if (slot < 0) inputWidth to inputHeight else slotSizes[slot]
                    Triple("${name}_raw", if (slot < 0) inputTexId else textures[slot], size)
                })
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, output)
        GLES20.glViewport(value[0], value[1], value[2], value[3])
        GLES20.glUseProgram(presentProgram)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(presentProgram, "uLuma"), if (model.luma && !copyOnly) 1f else 0f)
        bind(presentProgram, "uInput", 0, inputTexId)
        bind(presentProgram, "uResult", 1, if (copyOnly) inputTexId else textures[finalSlot])
        GLES20.glUniform2f(GLES20.glGetUniformLocation(presentProgram, "uOutSize"), value[2].toFloat(), value[3].toFloat())
        drawQuad(presentProgram)
        // Media3 binds textures on whatever unit is active.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun draw(program: Int, framebuffer: Int, width: Int, height: Int, inputs: List<Triple<String, Int, Pair<Int, Int>?>>) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uOutSize"), width.toFloat(), height.toFloat())
        inputs.forEachIndexed { unit, (uniform, texture, size) ->
            bind(program, uniform, unit, texture)
            if (size != null) {
                GLES20.glUniform2f(
                    GLES20.glGetUniformLocation(program, uniform.removeSuffix("_raw") + "_size"),
                    size.first.toFloat(), size.second.toFloat()
                )
            }
        }
        drawQuad(program)
    }

    private fun bind(program: Int, uniform: String, unit: Int, texture: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, uniform), unit)
    }

    private fun drawQuad(program: Int) {
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        // Client-side vertices: Media3 may have left its own buffer bound.
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
    }

    private fun link(fragment: String): Int {
        val es3 = fragment.startsWith("#version 300 es")
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, if (es3) VERTEX_300 else VERTEX_100))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "link: " + GLES20.glGetProgramInfoLog(program) }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "compile: " + GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    private fun deleteTextures() {
        if (textures.isNotEmpty()) GLES20.glDeleteTextures(textures.size, textures, 0)
        if (framebuffers.isNotEmpty()) GLES20.glDeleteFramebuffers(framebuffers.size, framebuffers, 0)
        textures = IntArray(0)
        framebuffers = IntArray(0)
    }

    override fun release() {
        super.release()
        deleteTextures()
        (programs.toList() + lumaProgram + presentProgram).filter { it != 0 }.forEach(GLES20::glDeleteProgram)
    }

    companion object {
        private val TEXTURE_PARAMETERS = listOf(
            GLES20.GL_TEXTURE_MIN_FILTER to GLES20.GL_LINEAR, GLES20.GL_TEXTURE_MAG_FILTER to GLES20.GL_LINEAR,
            GLES20.GL_TEXTURE_WRAP_S to GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_TEXTURE_WRAP_T to GLES20.GL_CLAMP_TO_EDGE
        )

        private const val VERTEX_300 = """#version 300 es
in vec2 aPosition;
void main() { gl_Position = vec4(aPosition, 0.0, 1.0); }"""

        private const val VERTEX_100 = """#version 100
attribute vec2 aPosition;
void main() { gl_Position = vec4(aPosition, 0.0, 1.0); }"""

        // mpv's LUMA plane: Y' from the frame's R'G'B' (BT.709, what HD video is coded in).
        private const val LUMA_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uInput;
uniform vec2 uOutSize;
out vec4 fragColor;
void main() {
  vec3 rgb = texture(uInput, gl_FragCoord.xy / uOutSize).rgb;
  fragColor = vec4(dot(rgb, vec3(0.2126, 0.7152, 0.0722)), 0.0, 0.0, 1.0);
}"""

        // ES 1.00 so that it also runs where the network can't (the copy).
        private const val PRESENT_FRAGMENT = """#version 100
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uInput;
uniform sampler2D uResult;
uniform vec2 uOutSize;
uniform float uLuma;
void main() {
  vec2 p = gl_FragCoord.xy / uOutSize;
  vec3 result = texture2D(uResult, p).rgb;
  if (uLuma > 0.5) {
    // The network's luma on the bilinearly doubled colour: only Y' changes.
    vec3 rgb = texture2D(uInput, p).rgb;
    result = rgb + (result.r - dot(rgb, vec3(0.2126, 0.7152, 0.0722)));
  }
  gl_FragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
}"""
    }
}
