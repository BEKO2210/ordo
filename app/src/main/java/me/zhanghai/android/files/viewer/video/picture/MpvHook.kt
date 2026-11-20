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

    fun fragment(pass: Pass): String {
        val names = LinkedHashSet(pass.binds)
        val hook = pass.hook
        if ("HOOKED_" in pass.body || (hook !in names && "${hook}_" in pass.body)) {
            names += hook
        }
        val sb = StringBuilder()
        sb.append("#version 300 es\n")
        sb.append("precision highp float; precision highp int; precision highp sampler2D;\n")
        sb.append("out vec4 fragColor;\n")
        sb.append("uniform vec2 uOutSize;\n")
        for (n in names) {
            sb.append("uniform sampler2D ${n}_raw; uniform vec2 ${n}_size;\n")
            // mpv/libplacebo: y=0 at top; GL: y=0 at bottom — flip so the body keeps mpv coords.
            sb.append("#define ${n}_pos (vec2(gl_FragCoord.x, uOutSize.y - gl_FragCoord.y) / uOutSize)\n")
            sb.append("#define ${n}_pt (1.0 / ${n}_size)\n")
            sb.append("#define ${n}_tex(p) (texture(${n}_raw, vec2((p).x, 1.0 - (p).y)))\n")
            sb.append("#define ${n}_texOff(off) (${n}_tex(${n}_pos + ${n}_pt * vec2(off)))\n")
            sb.append("#define ${n}_mul (1.0)\n")
        }
        sb.append("#define HOOKED_pos ${hook}_pos\n")
        sb.append("#define HOOKED_pt ${hook}_pt\n")
        sb.append("#define HOOKED_size ${hook}_size\n")
        sb.append("#define HOOKED_tex ${hook}_tex\n")
        sb.append("#define HOOKED_texOff ${hook}_texOff\n")
        sb.append("#define HOOKED_mul ${hook}_mul\n")
        sb.append(pass.body.trimEnd()).append('\n')
        sb.append("void main() { fragColor = hook(); }\n")
        return sb.toString()
    }

    fun target(pass: Pass): String = pass.save ?: pass.hook
}
