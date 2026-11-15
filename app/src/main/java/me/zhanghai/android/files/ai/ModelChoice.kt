/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

/**
 * Which on-device model a device gets. People only see "ready" or "not enough", never a model name.
 * Today there is one pinned model ([CommandModel]); a smaller tier needs its own pinned file, hash and device
 * measurement first (docs/KI-BEFEHLE.md), so weaker devices get no AI instead of an unmeasured one.
 */
enum class ModelChoice {
    QWEN_4B,
    TOO_WEAK;

    companion object {
        /**
         * [ramBytes] is ActivityManager.MemoryInfo.totalMem; [is64Bit] means the native library runs on this CPU
         * (shipped for arm64-v8a and x86_64 only); [dotProd] means an Arm CPU with dot-product instructions, the
         * only kind the 4B was measured on (KleidiAI's Q4_0 kernels need them).
         */
        fun pick(ramBytes: Long, is64Bit: Boolean, dotProd: Boolean): ModelChoice =
            if (is64Bit && dotProd && ramBytes >= CommandModel.MIN_RAM) QWEN_4B else TOO_WEAK

        /** True when the Features line of /proc/cpuinfo lists `asimddp` (Armv8.2 dot product). */
        fun hasDotProd(cpuinfo: String): Boolean =
            Regex("""^Features\s*:.*\basimddp\b""", RegexOption.MULTILINE).containsMatchIn(cpuinfo)
    }
}
