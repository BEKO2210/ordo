/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvHookTest {
    private fun asset(name: String) =
        File("src/main/assets/detailplus/$name").readText()

    @Test
    fun anime4kMHasNinePassesAndLastSavesMainAtDoubleWidth() {
        val passes = MpvHook.parse(asset("Anime4K_Upscale_CNN_x2_M.glsl"))
        assertEquals(9, passes.size)
        val last = passes.last()
        assertEquals("MAIN", last.save)
        assertEquals("conv2d_last_tf.w 2 *", last.width)
        assertEquals("MAIN", MpvHook.target(last))
    }

    @Test
    fun fsrcnnxHasFourteenPassesAndLastReplacesLuma() {
        val passes = MpvHook.parse(asset("FSRCNNX_x2_8-0-4-1.glsl"))
        assertEquals(14, passes.size)
        val last = passes.last()
        assertEquals(null, last.save)
        assertEquals("LUMA", MpvHook.target(last))
        assertEquals("LUMA.w 2 *", last.width)
    }

    @Test
    fun evalSizeExpressionsAndWhen() {
        val sizes = mapOf(
            "LUMA" to (640 to 360),
            "OUTPUT" to (1920 to 1080),
            "MAIN" to (640 to 360),
            "conv2d_tf" to (640 to 360)
        )
        assertEquals(1280f, MpvHook.eval("LUMA.w 2 *", sizes), 0f)
        assertEquals(360f, MpvHook.eval("conv2d_tf.h", sizes), 0f)
        assertEquals(
            1f,
            MpvHook.eval("OUTPUT.w LUMA.w / 1.300 > OUTPUT.h LUMA.h / 1.300 > *", sizes),
            0f
        )
        val smallOut = sizes + ("OUTPUT" to (640 to 360))
        assertEquals(
            0f,
            MpvHook.eval("OUTPUT.w LUMA.w / 1.300 > OUTPUT.h LUMA.h / 1.300 > *", smallOut),
            0f
        )
        try {
            MpvHook.eval("NOPE.w", sizes)
            throw AssertionError("expected throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("NOPE"))
        }
    }

    @Test
    fun parseRejectsTextureDirective() {
        try {
            MpvHook.parse("//!TEXTURE foo\n//!HOOK MAIN\nvec4 hook(){return vec4(0);}\n")
            throw AssertionError("expected throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("TEXTURE"))
        }
    }

    @Test
    fun fragmentDeclaresEachSamplerOnceWithFlippedPosAndWritesHarnessFiles() {
        val shaders = listOf(
            "Anime4K_Upscale_CNN_x2_M.glsl",
            "FSRCNNX_x2_8-0-4-1.glsl"
        )
        val outDir = File("build/detailplus-glsl").also { it.mkdirs() }
        for (name in shaders) {
            val passes = MpvHook.parse(asset(name))
            passes.forEachIndexed { i, pass ->
                val frag = MpvHook.fragment(pass)
                assertTrue(frag.startsWith("#version 300 es\n"))
                assertTrue(frag.contains("uOutSize.y - gl_FragCoord.y"))
                assertTrue(frag.endsWith("void main() { fragColor = hook(); }\n"))
                for (n in pass.binds) {
                    val decl = "uniform sampler2D ${n}_raw;"
                    assertEquals(1, Regex.fromLiteral(decl).findAll(frag).count())
                }
                File(outDir, "${name}_${i}.frag").writeText(frag)
            }
            // Multi-bind pass: still one sampler each.
            val multi = passes.first { it.binds.size > 1 }
            val frag = MpvHook.fragment(multi)
            for (n in multi.binds.toSet()) {
                assertEquals(1, Regex.fromLiteral("uniform sampler2D ${n}_raw;").findAll(frag).count())
            }
            assertFalse(frag.contains("uniform sampler2D ${multi.hook}_raw;uniform sampler2D ${multi.hook}_raw;"))
        }
    }
}
