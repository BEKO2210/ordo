/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
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
import me.zhanghai.android.files.BuildConfig
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
        private set

    /** From the player's GL thread and the offscreen timing's alike: one update at a time, none lost. */
    @Synchronized
    fun markUnsupported(model: DetailPlus.Model) {
        unsupported = unsupported + model
    }

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
 * Times a network offscreen, in its own OpenGL ES 3 context on a background thread, so "Automatic" never shows a
 * network it has not measured (W90: 81 ms a frame on screen for a second, 0.18 review).
 */
@OptIn(UnstableApi::class)
internal object DetailPlusBench {
    // shortcut: a 960x540 noise frame, scaled per megapixel; the fixed cost of a pass counts a little more than on a
    // film, so the estimate errs towards Lanczos. Time with the real first frame if that ever shuts out a device.
    private const val WIDTH = 960
    private const val HEIGHT = 540
    private const val WARM_UP = 3
    private const val RUNS = 8

    /**
     * GPU milliseconds per input megapixel at the top clock (median of the draws), or null where it can't run. GPU timer
     * per draw where the GPU has one (the wall time around glFinish was 3.5x too high on the Tab).
     */
    fun msPerMp(context: Context, model: DetailPlus.Model): Float? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE
        )
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
        val eglContext = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) return null
        val surface = EGL14.eglCreatePbufferSurface(
            display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        try {
            if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) return null
            return run(context, model)
        } catch (e: Exception) {
            Log.w(PICTURE_LOG_TAG, "Detail plus offscreen timing failed", e)
            return null
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, eglContext)
        }
    }

    private fun run(context: Context, model: DetailPlus.Model): Float? {
        val source = context.assets.open(model.asset).bufferedReader().use { it.readText() }
        val program = DetailPlusProgram(model, MpvHook.parse(source))
        try {
            // One draw on a quarter frame first: a budget GPU (81 ms a 540p frame) is told apart after ~40 ms of GPU
            // time instead of eleven full frames competing with the film's own Lanczos.
            val first = time(program, model, WIDTH / 2, HEIGHT / 2, warmUps = 1, runs = 1) ?: return null
            if (DetailPlus.clearlyTooSlow(first)) return first
            return time(program, model, WIDTH, HEIGHT, WARM_UP, RUNS)
        } finally {
            program.release()
        }
    }

    /** Median GPU ms per input megapixel at the top clock of [runs] draws at width x height, null if it can't run. */
    private fun time(
        program: DetailPlusProgram, model: DetailPlus.Model, width: Int, height: Int, warmUps: Int, runs: Int
    ): Float? {
        val textures = IntArray(2)
        val framebuffer = IntArray(1)
        val query = IntArray(1)
        val timer = "GL_EXT_disjoint_timer_query" in GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
        try {
            val size = program.configure(width, height)
            // Its init or configure found the GPU can't (it then only copies): nothing to time.
            if (model in DetailPlusGl.unsupported) return null
            GLES20.glGenTextures(2, textures, 0)
            val noise = ByteBuffer.allocateDirect(width * height * 4).apply {
                val random = java.util.Random(1)
                while (hasRemaining()) put(random.nextInt().toByte())
                flip()
            }
            texture(textures[0], width, height, noise)
            texture(textures[1], size.width, size.height, null)
            GLES20.glGenFramebuffers(1, framebuffer, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textures[1], 0
            )
            fun draw() {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
                GLES20.glViewport(0, 0, size.width, size.height)
                program.drawFrame(textures[0], 0)
            }
            repeat(warmUps) { draw() }
            GLES20.glFinish()
            if (timer) GLES30.glGenQueries(1, query, 0)
            val value = IntArray(1)
            // Each draw on its own, with the clock right after it: the governor moves the clock during the runs, and a
            // time at 220 MHz scaled by a clock read at 400 MHz came out twice too high (Tab, 21.11.2026).
            val times = List(runs) {
                GLES20.glGetError() // a stale error must not void this query
                val start = System.nanoTime()
                if (timer) GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, query[0])
                draw()
                if (timer) GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
                GLES20.glFinish()
                val wallMs = (System.nanoTime() - start) / 1e6f
                val clock = DetailPlusGl.clockShare() ?: 1f
                val gpuMs = if (timer) {
                    GLES20.glGetIntegerv(GL_GPU_DISJOINT_EXT, value, 0)
                    val disjoint = value[0] != 0
                    GLES30.glGetQueryObjectuiv(query[0], GLES30.GL_QUERY_RESULT, value, 0)
                    DetailPlus.timerMs(value[0].toLong() and 0xffffffffL, GLES20.glGetError(), disjoint)
                } else {
                    null
                }
                (gpuMs ?: wallMs) * clock * slowdown()
            }
            val perMp = times.sorted()[runs / 2] / (width * height / 1e6f)
            Log.i(PICTURE_LOG_TAG, "Detail plus $model offscreen ${width}x$height: " +
                "${times.joinToString { "%.2f".format(it) }} ms at the top clock, $perMp ms per megapixel")
            return perMp
        } finally {
            if (timer && query[0] != 0) GLES30.glDeleteQueries(1, query, 0)
            GLES20.glDeleteTextures(2, textures, 0)
            GLES20.glDeleteFramebuffers(1, framebuffer, 0)
        }
    }

    private fun texture(id: Int, width: Int, height: Int, pixels: ByteBuffer?) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    }

    /**
     * Debug builds only: `adb shell setprop debug.ordo.detailplus.slowdown 40` makes a fast GPU look like a budget one
     * (W90/A8 were unreachable for the 0.18 test). 1 when unset and in every release.
     */
    private fun slowdown(): Float = if (!BuildConfig.DEBUG) 1f else runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "debug.ordo.detailplus.slowdown") as String
    }.getOrNull()?.toFloatOrNull()?.takeIf { it > 0f } ?: 1f

    private const val EGL_OPENGL_ES3_BIT = 0x40
    private const val GL_TIME_ELAPSED_EXT = 0x88BF
    private const val GL_GPU_DISJOINT_EXT = 0x8FBB
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
            DetailPlusGl.markUnsupported(model)
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
        DetailPlusGl.markUnsupported(model)
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
