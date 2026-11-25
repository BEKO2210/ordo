/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import me.zhanghai.android.files.ai.CommandCheck.Outcome
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * Testebene 2: a delete sentence with an inserted negation (de/en) must never come back Ready.
 * Seeded loop instead of kotest-property: the module is JUnit 4.
 */
class CommandCheckPropertyTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var plan: CommandPlan

    @Before
    fun setUp() {
        root = temp.newFolder("0")
        File(root, "Download").mkdirs()
        plan = CommandPlan("delete", "Download")
    }

    @Test
    fun templatesWithoutNegation_areReady() {
        for (template in TEMPLATES) {
            val outcome = check(template)
            assertTrue("$template -> $outcome", outcome is Outcome.Ready)
        }
    }

    @Test
    fun insertedNegation_neverReady() {
        val random = Random(SEED)
        repeat(TRIALS) { trial ->
            val template = TEMPLATES[random.nextInt(TEMPLATES.size)]
            val negation = NEGATIONS[random.nextInt(NEGATIONS.size)].let { word ->
                if (random.nextBoolean()) word else {
                    word.replaceFirstChar { it.titlecase(Locale.ROOT) }
                }
            }
            val text = insert(template, negation, random)
            if (isAdditiveException(text)) return@repeat
            val outcome = check(text)
            assertTrue(
                "trial $trial: '$text' -> $outcome",
                outcome !is Outcome.Ready
            )
        }
    }

    private fun check(text: String) = CommandCheck.check(text, plan, null, root)

    companion object {
        private const val SEED = 0x4F52444FL
        private const val TRIALS = 400

        private val TEMPLATES = listOf(
            "lösche die Dateien in Download",
            "lösch alles in Download",
            "entferne die Fotos aus Download",
            "wirf die Videos in Download weg",
            "schmeiß die PDFs in Download weg",
            "schmeiss die Bilder in Download weg",
            "loesche alte Sachen in Download",
            "delete the files in Download",
            "remove everything from Download",
            "erase the photos in Download",
            "get rid of the videos in Download"
        )

        // Same vocabulary CommandCheck treats as negation; inserted as their own tokens.
        private val NEGATIONS = listOf(
            "nicht", "nichts", "nix", "kein", "keine", "keinen", "keiner", "keines", "keinerlei",
            "keinesfalls", "keineswegs", "weder", "nie", "niemals", "nimmer", "nirgends", "nein",
            "ohne", "außer", "ausser",
            "not", "no", "don't", "dont", "never", "nowhere", "neither", "nor", "none", "nothing",
            "except", "without",
            "no one", "auf keinen fall", "bloß nicht", "bloss nicht", "bitte nicht",
            "don\u2019t"
        )

        private val ADDITIVE = setOf("nur", "only", "just")
        private val ADDITIVE_CONT = setOf("auch", "also")

        private fun insert(template: String, negation: String, random: Random): String {
            val tokens = template.split(' ').filter { it.isNotEmpty() }.toMutableList()
            tokens.add(random.nextInt(tokens.size + 1), negation)
            return tokens.joinToString(" ")
        }

        private fun isAdditiveException(text: String): Boolean {
            val tokens = text.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
            var i = 0
            while (i < tokens.size - 1) {
                if (tokens[i] in setOf("nicht", "not") && tokens[i + 1] in ADDITIVE &&
                    tokens.drop(i + 2).any { it in ADDITIVE_CONT }
                ) {
                    return true
                }
                i++
            }
            return false
        }
    }
}
