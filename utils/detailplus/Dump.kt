/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import java.io.File

fun main(args: Array<String>) {
    val out = File(args[0]).also { it.mkdirs() }
    for (path in args.drop(1)) {
        val name = File(path).name
        MpvHook.parse(File(path).readText()).forEachIndexed { i, pass ->
            File(out, "${name}_$i.frag").writeText(MpvHook.fragment(pass))
        }
    }
}
