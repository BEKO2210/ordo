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
    fun breakableName(name: String): String =
        CAMEL.replace(SEPARATOR.replace(name, "$1$ZERO_WIDTH_SPACE"), ZERO_WIDTH_SPACE)

    private const val ZERO_WIDTH_SPACE = "\u200B"
    private val SEPARATOR = Regex("(?<=.)([._-])(?=\\S)")
    // "featureGraphic.png" may wrap at its inner capital: "feature" / "Graphic.png".
    private val CAMEL = Regex("(?<=\\p{Ll})(?=\\p{Lu})")

    /**
     * For a label of a few lines: once laid out, a text that still breaks inside a word (one long word without any
     * place to wrap) becomes a single line shortened in the middle, so the word is cut by "…" and never wrapped.
     */
    fun keepWordsWhole(view: android.widget.TextView) {
        view.post {
            val layout = view.layout ?: return@post
            val ends = IntArray((layout.lineCount - 1).coerceAtLeast(0)) { layout.getLineEnd(it) }
            if (midWord(layout.text, ends).isNotEmpty()) {
                view.maxLines = 1
                view.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
        }
    }

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
