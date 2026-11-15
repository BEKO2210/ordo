/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.docs

import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.ai.AiUnlock
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
    private val readmeDe = File(repo, "README.de.md").readText()

    private fun gigabytesDe(bytes: Long) = gigabytes(bytes).replace('.', ',')

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

    // The settings the READMEs name must be the ones the app shows: the entry names before " – " in each language.
    @Test
    fun readmesNameTheRealSettings() {
        val res = File(repo, "app/src/main/res")
        fun entries(values: String, array: String): List<String> {
            val xml = File(res, "$values/settings_ordo_strings.xml").readText()
            val body = Regex("<string-array name=\"$array\">(.*?)</string-array>", RegexOption.DOT_MATCHES_ALL)
                .find(xml)!!.groupValues[1]
            return Regex("<item>(.*?)</item>").findAll(body).map { it.groupValues[1].substringBefore(" –") }.toList()
        }
        for ((readmeText, values) in listOf(readme to "values", readmeDe to "values-de")) {
            for (name in entries(values, "settings_ai_speed_entries") + entries(values, "settings_motion_entries")) {
                assertTrue("README ($values) must name the setting \"$name\"",
                    readmeText.contains(name, ignoreCase = true))
            }
        }
    }

    @Test
    fun germanReadmeStatesTheSameFacts() {
        val quant = CommandModel.FILE_NAME.substringAfter("4b-").substringBefore(".gguf").uppercase(Locale.ROOT)
        assertTrue(readmeDe.contains("($quant)"))
        assertTrue(readmeDe.contains("rund ${gigabytesDe(CommandModel.SIZE)}"))
        assertTrue(readmeDe.contains("mindestens ${gigabytesDe(CommandModel.MIN_RAM)} RAM"))
        assertTrue(readmeDe.contains("rund ${gigabytesDe(CommandModel.SIZE + CommandModel.SPARE_SPACE)} freier Speicher"))
        assertTrue(readmeDe.contains("Version ${BuildConfig.VERSION_NAME}"))
        listOf("verschieben", "kopieren", "aufräumen", "Papierkorb").forEach { assertTrue(it, readmeDe.contains(it)) }
    }

    // Only the AI costs money; the Play Console product must be the one the code asks for.
    @Test
    fun docsStateTheAiPriceAndProduct() {
        assertTrue(readme.contains("one-time unlock for 0.99 EUR"))
        assertTrue(readmeDe.contains("einmalig für 0,99 € frei"))
        assertTrue(File(repo, "docs/PLAY-BERECHTIGUNGEN.md").readText().contains("`${AiUnlock.PRODUCT_ID}`"))
    }

    @Test
    fun readmeScreenshotsExist() {
        Regex("src=\"(docs/[^\"]+)\"").findAll(readme + readmeDe).forEach {
            assertTrue("missing ${it.groupValues[1]}", File(repo, it.groupValues[1]).exists())
        }
    }

    @Test
    fun readmeVersionAndAndroidMatchTheBuild() {
        assertTrue(readme.contains("version ${BuildConfig.VERSION_NAME}"))
        assertTrue(readme.contains("Android-6.0%2B"))
    }
}
