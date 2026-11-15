/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelChoiceTest {
    @Test
    fun thresholdIsInclusive() {
        assertEquals(ModelChoice.QWEN_4B, ModelChoice.pick(CommandModel.MIN_RAM, true))
        assertEquals(ModelChoice.TOO_WEAK, ModelChoice.pick(CommandModel.MIN_RAM - 1, true))
    }

    @Test
    fun realDevices() {
        // totalMem as phones report it: "6 GB" about 5.5 GB, Pixel 11 Pro XL 12 GB, old 4 GB phones.
        assertEquals(ModelChoice.QWEN_4B, ModelChoice.pick(5_500_000_000L, true))
        assertEquals(ModelChoice.QWEN_4B, ModelChoice.pick(11_500_000_000L, true))
        assertEquals(ModelChoice.TOO_WEAK, ModelChoice.pick(3_700_000_000L, true))
        assertEquals(ModelChoice.TOO_WEAK, ModelChoice.pick(0L, true))
    }

    @Test
    fun no64BitLibraryMeansNoAiWhateverTheRam() {
        assertEquals(ModelChoice.TOO_WEAK, ModelChoice.pick(16_000_000_000L, false))
        assertEquals(ModelChoice.TOO_WEAK, ModelChoice.pick(CommandModel.MIN_RAM, false))
    }
}
