/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ui

/**
 * The owner's rule that a word never breaks in the middle ("Absp-ielen", "Zufäll-ig"). Debug builds check every
 * visible text on screen with it (e2e/no-midword-breaks.sh reads what they log).
 */
object WordBreaks {
    /**
     * The line breaks of [text] that cut a word: a break whose characters on both sides are letters or digits of a
     * script that separates words with spaces. [lineEnds] are the layout's line ends (exclusive), the last one
     * included or not; lines that end in an ellipsis are left out by the caller.
     */
    fun midWord(text: CharSequence, lineEnds: IntArray): List<Int> =
        lineEnds.filter { end ->
            end > 0 && end < text.length && isWordChar(text[end - 1]) && isWordChar(text[end])
        }

    /**
     * A file name for a label of two lines: it may wrap after its dots, dashes and underscores ("01-first-light.|mp3")
     * instead of inside a word. With hyphenation off, Android's line breaker does not break after '-' either and cut
     * such names anywhere ("01-first-light.m|p3"); a zero-width space after each separator gives it the chance.
     */
    fun breakableName(name: String): String = SEPARATOR.replace(name, "$1​")

    private val SEPARATOR = Regex("(?<=.)([._-])(?=\\S)")

    private fun isWordChar(char: Char): Boolean {
        if (!char.isLetterOrDigit()) return false
        // Chinese, Japanese and Thai break between letters by design.
        return when (Character.UnicodeScript.of(char.code)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.THAI, Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER,
            Character.UnicodeScript.MYANMAR -> false
            else -> true
        }
    }
}
