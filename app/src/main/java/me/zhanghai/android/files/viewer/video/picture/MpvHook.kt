/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

/** mpv user-shader ("hook") parser and GLSL ES 3.00 fragment emitter for our FBO chain. */
internal object MpvHook {
    data class Pass(
        val desc: String,
        val hook: String,
        val binds: List<String>,
        val save: String?,
        val width: String?,
        val height: String?,
        val body: String
    )

    fun parse(source: String): List<Pass> {
        val lines = source.lineSequence().dropWhile { !it.startsWith("//!") }.toList()
        if (lines.isEmpty()) return emptyList()
        val out = mutableListOf<Pass>()
        var desc = ""
        var hook: String? = null
        var binds = mutableListOf<String>()
        var save: String? = null
        var width: String? = null
        var height: String? = null
        val body = StringBuilder()
        var inBody = false
        fun flush() {
            if (hook == null && binds.isEmpty() && body.isEmpty()) return
            val h = hook ?: throw IllegalArgumentException("pass without //!HOOK")
            out += Pass(desc, h, binds.toList(), save, width, height, body.toString().trimEnd())
            desc = ""
            hook = null
            binds = mutableListOf()
            save = null
            width = null
            height = null
            body.clear()
            inBody = false
        }
        for (line in lines) {
            if (line.startsWith("//!")) {
                if (inBody) flush()
                val rest = line.substring(3)
                val sp = rest.indexOf(' ')
                val key = if (sp < 0) rest else rest.substring(0, sp)
                val value = if (sp < 0) "" else rest.substring(sp + 1).trim()
                when (key) {
                    "DESC" -> desc = value
                    "HOOK" -> hook = value
                    "BIND" -> binds += value
                    "SAVE" -> save = value
                    "WIDTH" -> width = value
                    "HEIGHT" -> height = value
                    "WHEN", "COMPONENTS" -> Unit
                    // TEXTURE, BUFFER, PARAM, COMPUTE, OFFSET and anything newer: not needed by our two shaders.
                    else -> throw IllegalArgumentException("unsupported //!$key")
                }
            } else {
                inBody = true
                body.append(line).append('\n')
            }
        }
        flush()
        return out
    }

    fun eval(expression: String, sizes: Map<String, Pair<Int, Int>>): Float {
        val stack = ArrayDeque<Float>()
        fun pop(): Float =
            if (stack.isEmpty()) throw IllegalArgumentException("malformed") else stack.removeLast()
        for (tok in expression.trim().split(Regex("\\s+"))) {
            when (tok) {
                "+" -> stack.addLast(pop().let { b -> pop() + b })
                "-" -> stack.addLast(pop().let { b -> pop() - b })
                "*" -> stack.addLast(pop().let { b -> pop() * b })
                "/" -> stack.addLast(pop().let { b -> pop() / b })
                ">" -> stack.addLast(pop().let { b -> if (pop() > b) 1f else 0f })
                "<" -> stack.addLast(pop().let { b -> if (pop() < b) 1f else 0f })
                "=" -> stack.addLast(pop().let { b -> if (pop() == b) 1f else 0f })
                "!" -> stack.addLast(if (pop() == 0f) 1f else 0f)
                else -> {
                    val num = tok.toFloatOrNull()
                    if (num != null) {
                        stack.addLast(num)
                        continue
                    }
                    val dot = tok.indexOf('.')
                    if (dot <= 0) throw IllegalArgumentException(tok)
                    val name = tok.substring(0, dot)
                    val (w, h) = sizes[name]
                        ?: throw IllegalArgumentException("unknown $name")
                    stack.addLast(
                        when (tok.substring(dot + 1)) {
                            "w", "width" -> w.toFloat()
                            "h", "height" -> h.toFloat()
                            else -> throw IllegalArgumentException(tok)
                        }
                    )
                }
            }
        }
        if (stack.size != 1) throw IllegalArgumentException("malformed")
        return stack.single()
    }

    /**
     * Consecutive passes drawn in one draw into several render targets (at most [MAX_TARGETS]): same plane and size,
     * none reads what an earlier one of the group writes, and no macros two bodies might both define. FSRCNNX's pairs
     * (feature 1/2, mapping n_1/n_2, residuals 1/2) then read their inputs once instead of twice.
     */
    fun groups(passes: List<Pass>): List<List<Pass>> {
        val groups = mutableListOf<MutableList<Pass>>()
        for (pass in passes) {
            val group = groups.lastOrNull()
            if (group != null && group.size < MAX_TARGETS && group.all { fusable(it, pass) }) group += pass
            else groups += mutableListOf(pass)
        }
        return groups
    }

    private fun fusable(a: Pass, b: Pass): Boolean =
        a.hook == b.hook && a.width == b.width && a.height == b.height && target(a) !in b.binds + b.hook &&
            target(a) != target(b) && "#define" !in a.body && "#define" !in b.body

    fun fragment(pass: Pass): String = fragment(listOf(pass))

    /**
     * One GLSL ES 3.00 fragment shader for [group] (one output per pass). [rgbLuma]: the LUMA plane is the RGB frame
     * itself and its samples are turned into Y' on the fly (BT.709), so no separate luma pass and texture. [sameSize]:
     * every bound texture has the output's size, so `_texOff` reads whole texels (texelFetch, edges clamped as
     * CLAMP_TO_EDGE does) instead of filtering at texel centres. [main] replaces the default main() (the last pass
     * drawn straight onto the output).
     */
    fun fragment(group: List<Pass>, rgbLuma: Boolean = false, sameSize: Boolean = false, main: String? = null): String {
        val names = LinkedHashSet<String>()
        val hook = group.first().hook
        for (pass in group) names += pass.binds
        if (group.any { "HOOKED_" in it.body || (hook !in names && "${hook}_" in it.body) }) names += hook
        val sb = StringBuilder()
        sb.append("#version 300 es\n")
        // The networks' sums run in mediump, which mobile GPUs execute at twice the rate, on samples that are half
        // floats anyway. Positions stay highp (fp16 can't address a 1080p texel): reading whole texels needs none,
        // otherwise everything but the sums stays highp.
        sb.append("precision ${if (sameSize) "mediump" else "highp"} float; precision highp int; precision mediump sampler2D;\n")
        if (main == null && group.size == 1) sb.append("out vec4 fragColor;\n")
        else if (main == null) group.indices.forEach { sb.append("layout(location = $it) out vec4 fragColor$it;\n") }
        sb.append("uniform highp vec2 uOutSize;\n")
        for (n in names) {
            sb.append("uniform sampler2D ${n}_raw; uniform highp vec2 ${n}_size;\n")
            // mpv/libplacebo: y=0 at top; GL: y=0 at bottom — flip so the body keeps mpv coords.
            sb.append("#define ${n}_pos (vec2(gl_FragCoord.x, uOutSize.y - gl_FragCoord.y) / uOutSize)\n")
            sb.append("#define ${n}_pt (1.0 / ${n}_size)\n")
            val sample = if (rgbLuma && n == "LUMA") "vec4(dot((v).rgb, vec3(0.2126, 0.7152, 0.0722)))" else "(v)"
            sb.append("#define ${n}_sample(v) $sample\n")
            sb.append("#define ${n}_tex(p) ${n}_sample(texture(${n}_raw, vec2((p).x, 1.0 - (p).y)))\n")
            if (sameSize) {
                // mpv's offsets point down in y, GL's texel rows up.
                sb.append("#define ${n}_texOff(off) ${n}_sample(texelFetch(${n}_raw, clamp(ivec2(gl_FragCoord.xy) + ")
                sb.append("ivec2(vec2(off)) * ivec2(1, -1), ivec2(0), ivec2(${n}_size) - 1), 0))\n")
            } else {
                sb.append("#define ${n}_texOff(off) (${n}_tex(${n}_pos + ${n}_pt * vec2(off)))\n")
            }
            sb.append("#define ${n}_mul (1.0)\n")
        }
        sb.append("#define HOOKED_pos ${hook}_pos\n")
        sb.append("#define HOOKED_pt ${hook}_pt\n")
        sb.append("#define HOOKED_size ${hook}_size\n")
        sb.append("#define HOOKED_tex ${hook}_tex\n")
        sb.append("#define HOOKED_texOff ${hook}_texOff\n")
        sb.append("#define HOOKED_mul ${hook}_mul\n")
        group.forEachIndexed { i, pass ->
            val body = MEDIUMP_SUM.replace(pass.body.trimEnd(), "$1mediump vec4 ")
            sb.append(if (group.size == 1) body else HOOK_CALL.replace(body, "hook$i(")).append('\n')
        }
        when {
            main != null -> sb.append(main).append('\n')
            group.size == 1 -> sb.append("void main() { fragColor = hook(); }\n")
            else -> sb.append("void main() { ")
                .append(group.indices.joinToString(" ") { "fragColor$it = hook$it();" }).append(" }\n")
        }
        return sb.toString()
    }

    /** At most as many as OpenGL ES 3.0 guarantees (GL_MAX_DRAW_BUFFERS >= 4). */
    const val MAX_TARGETS = 4

    // The accumulators of both networks ("vec4 res", "vec4 result"); coordinates elsewhere in a body stay highp.
    private val MEDIUMP_SUM = Regex("(?m)^(\\s*)vec4 (?=res\\b|result\\b)")
    private val HOOK_CALL = Regex("\\bhook\\s*\\(")

    fun target(pass: Pass): String = pass.save ?: pass.hook
}
