/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelChoiceTest {
    @Test
    fun thresholdIsInclusive() {
        assertEquals(ModelChoice.QWEN_4B, ModelChoice.pick(CommandModel.MIN_RAM, true, true))
        assertEquals(ModelChoice.TOO_WEAK, ModelChoice.pick(CommandModel.MIN_RAM - 1, true, true))
    }

    @Test
    fun realDevices() {
        // totalMem: 12 GB devices (the only class the 4B was measured on) above 10 GB, "8 GB" and "6 GB" phones below.
        assertEquals(ModelChoice.QWEN_4B, ModelChoice.pick(11_500_000_000L, true, true))
        assertEquals(ModelChoice.QWEN_4B, ModelChoice.pick(10_800_000_000L, true, true))
        assertEquals(ModelChoice.TOO_WEAK, ModelChoice.pick(8_589_934_592L, true, true))
        assertEquals(ModelChoice.TOO_WEAK, ModelChoice.pick(5_500_000_000L, true, true))
        assertEquals(ModelChoice.TOO_WEAK, ModelChoice.pick(0L, true, true))
    }

    @Test
    fun no64BitLibraryMeansNoAiWhateverTheRam() {
        assertEquals(ModelChoice.TOO_WEAK, ModelChoice.pick(16_000_000_000L, false, true))
        assertEquals(ModelChoice.TOO_WEAK, ModelChoice.pick(CommandModel.MIN_RAM, false, true))
    }

    @Test
    fun noDotProductMeansNoAiWhateverTheRam() {
        assertEquals(ModelChoice.TOO_WEAK, ModelChoice.pick(12_000_000_000L, true, false))
    }

    @Test
    fun readsDotProductFromCpuinfo() {
        // Snapdragon 8 Gen 2 (Tab S9 Ultra)
        assertTrue(ModelChoice.hasDotProd("processor\t: 0\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 " +
            "atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 asimdfhm i8mm bf16\n"))
        // Snapdragon 835 (Armv8.0), x86_64 emulator, unreadable file
        assertFalse(ModelChoice.hasDotProd("processor\t: 0\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n"))
        assertFalse(ModelChoice.hasDotProd("processor\t: 0\nflags\t\t: fpu sse sse2 ssse3 sse4_1 sse4_2 avx avx2\n"))
        assertFalse(ModelChoice.hasDotProd(""))
    }
}
