/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.docs

import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.ai.CommandCheck
import me.zhanghai.android.files.ai.CommandModel
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * README.md and docs/KI-BEFEHLE.md must say what the code does. When a fact here changes in the code, the docs
 * have to change with it, or this test fails.
 */
class DocsConsistencyTest {
    private val repo = File(System.getProperty("user.dir")!!).let { if (File(it, "README.md").exists()) it else it.parentFile!! }
    private val readme = File(repo, "README.md").readText()
    private val aiDoc = File(repo, "docs/KI-BEFEHLE.md").readText()

    private fun gigabytes(bytes: Long) = String.format(Locale.ROOT, "%.1f GB", bytes / 1e9)

    @Test
    fun readmeNamesTheShippedModelAndItsSize() {
        val quant = CommandModel.FILE_NAME.substringAfter("4b-").substringBefore(".gguf").uppercase(Locale.ROOT)
        assertTrue("README must name quant $quant", readme.contains("($quant)"))
        assertTrue("README must state model size", readme.contains("about ${gigabytes(CommandModel.SIZE)}"))
        assertTrue("KI-BEFEHLE.md must mark $quant as chosen",
            aiDoc.contains("**Qwen3.5-4B $quant** (gewählt"))
    }

    @Test
    fun readmeStatesTheRealRequirements() {
        assertTrue("README must state min RAM", readme.contains("at least ${gigabytes(CommandModel.MIN_RAM)} RAM"))
        assertTrue("README must state free space",
            readme.contains("about ${gigabytes(CommandModel.SIZE + CommandModel.SPARE_SPACE)} free space"))
    }

    @Test
    fun readmeDescribesEveryAction() {
        val words = mapOf("move" to "move", "copy" to "copy", "organize" to "tidy up", "delete" to "trash")
        CommandCheck.ACTIONS.forEach { action ->
            assertTrue("README must describe action $action", readme.contains(words.getValue(action)))
        }
    }

    @Test
    fun readmeVersionAndAndroidMatchTheBuild() {
        assertTrue(readme.contains("version ${BuildConfig.VERSION_NAME}"))
        assertTrue(readme.contains("Android-6.0%2B"))
    }
}
