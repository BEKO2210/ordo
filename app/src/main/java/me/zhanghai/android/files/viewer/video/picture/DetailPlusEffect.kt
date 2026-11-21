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
import java.io.File
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

    /**
     * The GPU's clock now as a share of its top clock (Adreno's devfreq), or null where it can't be read. The governor
     * slows the GPU down while it has time to spare (Tab S9 Ultra: 295 of 719 MHz while playing 540p), so the timer's
     * milliseconds at that clock overstate what the network takes once the GPU needs to hurry.
     */
    fun clockShare(): Float? = runCatching {
        fun read(name: String) = File("/sys/class/kgsl/kgsl-3d0/devfreq/$name").readText().trim().toFloat()
        (read("cur_freq") / read("max_freq")).takeIf { it > 0f && it <= 1f }
    }.getOrNull()
}

/**
 * Runs the mpv passes in its own RGBA16F textures (the networks' features are signed and need more than Media3's
 * 8-bit passes) and draws the last one, the doubling, straight into Media3's output: the RGB of Anime4K directly,
 * FSRCNNX's luma onto the bilinearly doubled colour. FSRCNNX reads its luma from the frame itself and draws its pairs
 * of passes together ([MpvHook.groups]). On a GPU that can't, it copies the frame unchanged and sets
 * [DetailPlusGl.unsupported], so the player switches back to Lanczos alone.
 */
@OptIn(UnstableApi::class)
private class DetailPlusProgram(private val model: DetailPlus.Model, passes: List<MpvHook.Pass>) :
    BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1) {
    private class Step(
        val program: Int, val width: Int, val height: Int, val framebuffer: Int, val inputs: List<Pair<String, Int>>
    )

    private val plane = if (model.luma) "LUMA" else "MAIN"
    private val groups = MpvHook.groups(passes)
    private var programs = IntArray(0)
    private var copyProgram = 0
    private var copyOnly = false
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0) }

    // Per configure: textures, a framebuffer per step, the inputs of each step and of the last pass (-1 = Media3's).
    private var textures = IntArray(0)
    private var framebuffers = IntArray(0)
    private var steps = emptyList<Step>()
    private var slotSizes = ArrayList<Pair<Int, Int>>()
    private var finalInputs = emptyList<Pair<String, Int>>()
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
            val last = groups.last().single()
            check(MpvHook.target(last) == plane) { "last pass writes ${MpvHook.target(last)}" }
            // Y' comes from the frame's R'G'B' until a pass replaces the plane (only the last one does).
            var lumaIsFrame = model.luma
            programs = IntArray(groups.size) { index ->
                val group = groups[index]
                val main = if (index == groups.lastIndex) (if (model.luma) FINAL_LUMA_MAIN else FINAL_RGB_MAIN) else null
                // Every pass before the last runs at the source size on textures of that size (checked in configure).
                link(MpvHook.fragment(group, lumaIsFrame, sameSize = main == null, main = main)).also {
                    if (group.any { MpvHook.target(it) == plane }) lumaIsFrame = false
                }
            }
        } catch (e: Exception) {
            Log.w(PICTURE_LOG_TAG, "Detail plus can't run here, copying instead", e)
            DetailPlusGl.unsupported += model
            copyOnly = true
            copyProgram = runCatching { link(COPY_FRAGMENT) }.getOrDefault(0)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        deleteTextures()
        if (copyOnly) return Size(inputWidth, inputHeight)
        // Each group writes into free textures of its size; a texture is free again after the last group reading it
        // (taken before freeing, so a group's outputs never alias its own inputs).
        val sizes = HashMap<String, Pair<Int, Int>>().apply { put(plane, inputWidth to inputHeight) }
        val lastUse = HashMap<String, Int>()
        groups.forEachIndexed { index, group -> group.forEach { pass -> (pass.binds + pass.hook).forEach { lastUse[it] = index } } }
        slotSizes = ArrayList()
        val free = ArrayList<Int>()
        val holder = HashMap<String, Int>().apply { put(plane, -1) }
        fun take(size: Pair<Int, Int>): Int =
            free.firstOrNull { slotSizes[it] == size }?.also { free.remove(it) } ?: slotSizes.size.also { slotSizes += size }
        val planned = ArrayList<Triple<Pair<Int, Int>, List<Int>, List<Pair<String, Int>>>>()
        groups.forEachIndexed { index, group ->
            val first = group.first()
            val hooked = sizes.getValue(first.hook)
            val size = (first.width?.let { MpvHook.eval(it, sizes).toInt() } ?: hooked.first) to
                (first.height?.let { MpvHook.eval(it, sizes).toInt() } ?: hooked.second)
            val inputs = group.flatMap { it.binds + it.hook }.distinct().map { it to holder.getValue(it) }
            if (index == groups.lastIndex) {
                finalInputs = inputs
                sizes[plane] = size
                return@forEachIndexed
            }
            // The texelFetch reads ([MpvHook.fragment] sameSize) need it; neither shader does otherwise.
            if (inputs.any { (name, _) -> sizes.getValue(name) != size }) return copyInstead("inputs of another size")
            val slots = group.map { take(size) }
            for ((name, slot) in inputs) {
                if (lastUse[name] == index) {
                    holder.remove(name)
                    if (slot >= 0 && slot !in holder.values) free += slot
                }
            }
            group.forEachIndexed { i, pass ->
                val target = MpvHook.target(pass)
                holder.put(target, slots[i])?.let { old -> if (old >= 0 && old !in holder.values) free += old }
                sizes[target] = size
            }
            planned += Triple(size, slots, inputs)
        }
        textures = IntArray(slotSizes.size)
        GLES20.glGenTextures(textures.size, textures, 0)
        slotSizes.forEachIndexed { slot, (width, height) ->
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[slot])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, width, height, 0, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, null
            )
            for ((name, value) in TEXTURE_PARAMETERS) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, name, value)
        }
        framebuffers = IntArray(planned.size)
        GLES20.glGenFramebuffers(framebuffers.size, framebuffers, 0)
        steps = planned.mapIndexed { index, (size, slots, inputs) ->
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[index])
            slots.forEachIndexed { i, slot ->
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0 + i, GLES20.GL_TEXTURE_2D, textures[slot], 0
                )
            }
            GLES30.glDrawBuffers(slots.size, IntArray(slots.size) { GLES20.GL_COLOR_ATTACHMENT0 + it }, 0)
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) return copyInstead("framebuffer incomplete ($status)")
            Step(programs[index], size.first, size.second, framebuffers[index], inputs)
        }
        val final = sizes.getValue(plane)
        return Size(final.first, final.second)
    }

    private fun copyInstead(why: String): Size {
        Log.w(PICTURE_LOG_TAG, "Detail plus: $why, copying instead")
        DetailPlusGl.unsupported += model
        copyOnly = true
        if (copyProgram == 0) copyProgram = runCatching { link(COPY_FRAGMENT) }.getOrDefault(0)
        deleteTextures()
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val value = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, value, 0)
        val output = value[0]
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, value, 0)
        if (copyOnly) {
            GLES20.glUseProgram(copyProgram)
            bind(copyProgram, "uInput", 0, inputTexId)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(copyProgram, "uOutSize"), value[2].toFloat(), value[3].toFloat())
            drawQuad(copyProgram)
        } else {
            for (step in steps) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, step.framebuffer)
                GLES20.glViewport(0, 0, step.width, step.height)
                draw(step.program, step.width, step.height, step.inputs, inputTexId)
            }
            // The doubling itself, straight into Media3's output (no 2x half-float texture written and read again).
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, output)
            GLES20.glViewport(value[0], value[1], value[2], value[3])
            val program = programs.last()
            // glUniform* sets the program in use: the colour sampler only after it is.
            GLES20.glUseProgram(program)
            if (model.luma) bind(program, "uInput", finalInputs.size, inputTexId)
            draw(program, value[2], value[3], finalInputs, inputTexId)
        }
        // Media3 binds textures on whatever unit is active.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun draw(program: Int, width: Int, height: Int, inputs: List<Pair<String, Int>>, inputTexId: Int) {
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uOutSize"), width.toFloat(), height.toFloat())
        inputs.forEachIndexed { unit, (name, slot) ->
            bind(program, "${name}_raw", unit, if (slot < 0) inputTexId else textures[slot])
            val size = if (slot < 0) inputWidth to inputHeight else slotSizes[slot]
            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(program, "${name}_size"), size.first.toFloat(), size.second.toFloat()
            )
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
        val vertex = compile(GLES20.GL_VERTEX_SHADER, if (es3) VERTEX_300 else VERTEX_100)
        val shader = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, shader)
        GLES20.glLinkProgram(program)
        // The linked program keeps what it needs; the shader objects would only leak with every rebuild.
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(shader)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            error("link: $log")
        }
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
        (programs.toList() + copyProgram).filter { it != 0 }.forEach(GLES20::glDeleteProgram)
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

        // The last pass's main(): the network's luma on the bilinearly doubled colour (only Y' changes, BT.709 as HD
        // video is coded) or the network's RGB.
        private const val FINAL_LUMA_MAIN = """uniform sampler2D uInput;
out vec4 fragColor;
void main() {
  float y = hook().x;
  vec3 rgb = texture(uInput, gl_FragCoord.xy / uOutSize).rgb;
  fragColor = vec4(clamp(rgb + (y - dot(rgb, vec3(0.2126, 0.7152, 0.0722))), 0.0, 1.0), 1.0);
}"""

        private const val FINAL_RGB_MAIN = """out vec4 fragColor;
void main() { fragColor = vec4(clamp(hook().rgb, 0.0, 1.0), 1.0); }"""

        // ES 1.00 so that it also runs where the network can't.
        private const val COPY_FRAGMENT = """#version 100
precision mediump float;
uniform sampler2D uInput;
uniform vec2 uOutSize;
void main() { gl_FragColor = vec4(texture2D(uInput, gl_FragCoord.xy / uOutSize).rgb, 1.0); }"""
    }
}
