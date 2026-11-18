/*
 *     Copyright (C) 2024 nift4
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

/*
 * Ordo: taken from Gramophone (FoedusProgramme/Gramophone on GitHub, commit f8eb02d,
 * app/src/main/java/org/akanework/gramophone/logic/utils/SemanticLyrics.kt). Kept: the LRC parser (plain, compressed,
 * enhanced word timestamps, [offset:], metadata, speakers) and the SYLT-to-lines conversion. Removed: Parcelable,
 * TTML, SRT and the USLT decoder (jaudiotagger reads USLT and the SYLT frame header for Ordo).
 */

package me.zhanghai.android.files.viewer.audio.lyrics

import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import me.zhanghai.android.files.viewer.audio.lyrics.SemanticLyrics.LyricLine
import me.zhanghai.android.files.viewer.audio.lyrics.SemanticLyrics.SyncedLyrics
import me.zhanghai.android.files.viewer.audio.lyrics.SemanticLyrics.UnsyncedLyrics
import me.zhanghai.android.files.viewer.audio.lyrics.SemanticLyrics.Word


/*
 * Syntactic-semantic lyric parser.
 *   First parse lrc syntax into custom objects, then parse these into usable representation
 *   for playback. This should be more testable and stable than the old parser.
 *
 * Formats we have to consider in this parser are:
 *  - Simple LRC files (ref Wikipedia) ex: [00:11.22] hello i am lyric
 *  - "compressed LRC" with >1 tag for repeating line ex: [00:11.22][00:15.33] hello i am lyric
 *  - Invalid LRC with all-zero tags [00:00.00] hello i am lyric
 *  - Lyrics that aren't synced and have no tags at all
 *  - Translations, type 1 (ex: pasting first japanese and then english lrc file into one file)
 *     - This implies multiple [offset:] tags are possible
 *  - Translations, type 2 (ex: translated line directly under previous non-translated line)
 *  - The timestamps can variate in the following ways: [00:11] [00:11:22] [00:11.22] [00:11.222] [00:11:222]
 *  - Multiline format: This technically isn't part of any listed guidelines, however is allows for
 *      reading of otherwise discarded lyrics, all the lines between sync point A and B are read as
 *      lyric text of A
 *  - Extended LRC (ref Wikipedia) ex: [00:11.22] <00:11.22> hello <00:12.85> i am <00:13.23> lyric
 *  - iTunes dual speaker extension (v1: / v2: / [bg: ])
 *  - [offset:] tag in header (ref Wikipedia)
 * We completely ignore all ID3 tags from the header as MediaStore is our source of truth.
 */

// This does not encode the full range of possible information in the TTML format, only what's used
// for actual rendering. This could change in the future, but for now it's like this.
enum class SpeakerEntity(
    val isVoice2: Boolean = false, // align opposite
    val isGroup: Boolean = false, // center
    val isBackground: Boolean = false, // small
    val isWidthLimited: Boolean = false,
) {
    // Voice is used if there should be no width limit as it's the only person, Voice1 is used if
    // there are more and hence width limit should be applied.
    Voice,
    VoiceBackground(isBackground = true),
    Voice1(isWidthLimited = true),
    Voice1Background(isWidthLimited = true, isBackground = true),
    Voice2(isWidthLimited = true, isVoice2 = true),
    Voice2Background(isWidthLimited = true, isVoice2 = true, isBackground = true),
    Group(isGroup = true),
    GroupBackground(isGroup = true, isBackground = true)
}

/*
 * Syntactic lyric parser. Parses lrc syntax into custom objects.
 *
 * Formats we have to consider in this component are:
 *  - Simple LRC files (ref Wikipedia) ex: [00:11.22] hello i am lyric
 *  - Invalid LRC with all-zero tags [00:00.00] hello i am lyric
 *  - Lyrics that aren't synced and have no tags at all
 *  - The timestamps can variate in the following ways: [00:11] [00:11:22] [00:11.22] [00:11.222] [00:11:222]
 *  - Multiline format: This technically isn't part of any listed guidelines, however is allows for
 *      reading of otherwise discarded lyrics, all the lines between sync point A and B are read as
 *      lyric text of A
 *  - Extended LRC (ref Wikipedia) ex: [00:11.22] <00:11.22> hello <00:12.85> i am <00:13.23> lyric
 *  - Extended LRC without sync points ex: <00:11.22> hello <00:12.85> i am <00:13.23> lyric
 *  - iTunes dual speaker extension (v1: / v2: / [bg: ])
 *  - Metadata tags in header (ref Wikipedia)
 */
private sealed class SyntacticLrc {
    // all timestamps are in milliseconds ignoring offset
    data class SyncPoint(val timestamp: ULong) : SyntacticLrc()
    data class LineEndSyncPoint(val timestamp: ULong) : SyntacticLrc()
    data class SpeakerTag(val speaker: SpeakerEntity) : SyntacticLrc()
    data class WordSyncPoint(val timestamp: ULong) : SyntacticLrc()
    data class Metadata(val name: String, val value: String) : SyntacticLrc()
    data class LyricText(val text: String) : SyntacticLrc()
    data class InvalidText(val text: String) : SyntacticLrc()
    open class NewLine : SyntacticLrc() {
        class SyntheticNewLine : NewLine()
    }

    companion object {
        // also eats space if present
        val timeMarksRegex = "\\[(\\d+)[.:](\\d+)([.:]\\d+)?(?:-(\\d+)[.:](\\d+)([.:]\\d+)?)?]".toRegex()
        val timeMarksAfterWsRegex = "([ \t]+)\\[\\d+[.:]\\d+(?:[.:]\\d+)?(?:-\\d+[.:]\\d+(?:[.:]\\d+)?)?]".toRegex()
        val timeWordMarksRegex = "<(\\d+)[.:](\\d+)([.:]\\d+)?>".toRegex()
        val metadataRegex = "\\[([a-zA-Z#]+):([^]]*)]".toRegex()

        private fun parseTime(match: MatchResult, second: Boolean): ULong {
            val offset = if (second) 3 else 0
            val minute = match.groupValues[offset+1].toULong()
            val milliseconds = ((match.groupValues[offset+2] + match.groupValues[offset+3]
                .replace(':', '.')).toDouble() * 1000L).toULong()
            return minute * 60u * 1000u + milliseconds
        }

        fun parseLrc(text: String, multiLineEnabled: Boolean): List<SyntacticLrc>? {
            if (text.isBlank()) return null
            var pos = 0
            val out = mutableListOf<SyntacticLrc>()
            var isBgSpeaker = false
            while (pos < text.length) {
                var pendingBgNewLine = false
                if (isBgSpeaker && text[pos] == ']') {
                    pos++
                    isBgSpeaker = false
                    pendingBgNewLine = true
                }
                if (pos < text.length && pos + 1 < text.length && text.regionMatches(
                        pos,
                        "\r\n",
                        0,
                        2
                    )
                ) {
                    out.add(NewLine())
                    pos += 2
                    continue
                }
                if (pos < text.length && (text[pos] == '\n' || text[pos] == '\r')) {
                    out.add(NewLine())
                    pos++
                    continue
                }
                if (pendingBgNewLine) {
                    out.add(NewLine.SyntheticNewLine())
                    continue
                }
                val tmMatch = timeMarksRegex.matchAt(text, pos)
                if (tmMatch != null) {
                    // Insert synthetic newlines at places where we'd expect one. This won't ever
                    // work with word lyrics without normal sync points at all for obvious reasons,
                    // but hey, we tried. Can't do much about it.
                    // If you want to write something that looks like a timestamp into your lyrics,
                    // you'll probably have to delete the following three lines.
                    val lastOrNull = out.lastOrNull()
                    if (!(lastOrNull is NewLine? || lastOrNull is SyncPoint
                                || lastOrNull is LineEndSyncPoint
                                || (lastOrNull is SpeakerTag && lastOrNull.speaker.isBackground)))
                        out.add(NewLine.SyntheticNewLine())
                    out.add(SyncPoint(parseTime(tmMatch, false)))
                    if (tmMatch.groupValues[4].isNotEmpty()) { // [00:01.02-00:03.03] duration ext
                        out.add(LineEndSyncPoint(parseTime(tmMatch, true)))
                    }
                    pos += tmMatch.value.length
                    continue
                }
                // Skip spaces in between of compressed lyric sync points. They really are
                // completely useless information we can and should discard.
                val tmwMatch = timeMarksAfterWsRegex.matchAt(text, pos)
                if (out.lastOrNull() is SyncPoint && pos + 7 < text.length && tmwMatch != null) {
                    pos += tmwMatch.groupValues[1].length
                    continue
                }
                // Speaker points can only appear directly after a sync point
                if (out.lastOrNull() is SyncPoint || out.lastOrNull() is LineEndSyncPoint) {
                    if (pos + 2 < text.length && text.regionMatches(pos, "v1:", 0, 3)) {
                        out.add(SpeakerTag(SpeakerEntity.Voice1))
                        pos += 3
                        continue
                    }
                    if (pos + 2 < text.length && text.regionMatches(pos, "v2:", 0, 3)) {
                        out.add(SpeakerTag(SpeakerEntity.Voice2))
                        pos += 3
                        continue
                    }
                    if (pos + 2 < text.length && text.regionMatches(pos, "v3:", 0, 3)) {
                        out.add(SpeakerTag(SpeakerEntity.Group))
                        pos += 3
                        continue
                    }
                    if (pos + 3 < text.length && text.regionMatches(pos, " v1:", 0, 4)) {
                        out.add(SpeakerTag(SpeakerEntity.Voice1))
                        pos += 4
                        continue
                    }
                    if (pos + 3 < text.length && text.regionMatches(pos, " v2:", 0, 4)) {
                        out.add(SpeakerTag(SpeakerEntity.Voice2))
                        pos += 4
                        continue
                    }
                    if (pos + 3 < text.length && text.regionMatches(pos, " v3:", 0, 4)) {
                        out.add(SpeakerTag(SpeakerEntity.Group))
                        pos += 4
                        continue
                    }
                }
                // Metadata (or the bg speaker, which looks like metadata) can only appear in the
                // beginning of a file or after newlines
                if (pos + 3 < text.length && text.regionMatches(pos, "[bg:", 0, 4)) {
                    // Insert synthetic newlines at places where we'd expect one.
                    // If you want to write [bg: into your lyrics, you'll probably have to add the
                    // conditional surrounding this comment into the
                    // if (out.isEmpty() || out.last() is NewLine) below.
                    if (out.isNotEmpty() && out.last() !is NewLine)
                        out.add(NewLine.SyntheticNewLine())
                    val lastSpeaker = if (out.isNotEmpty()) out.subList(0, out.size - 1)
                        .indexOfLast { it is NewLine }.let { if (it < 0) null else it }?.let {
                            (out.subList(it, out.size - 1).findLast { it is SpeakerTag }
                                    as SpeakerTag?)?.speaker
                        } else null
                    // TODO revisit this heuristic. iTunes bg lines are a child of the last main
                    //  line and there is no "opposite" flag for background lines, but reality does
                    //  not work that way. can main lines be empty in iTunes' lyrics?
                    out.add(
                        SpeakerTag(
                            when {
                                lastSpeaker?.isGroup == true -> SpeakerEntity.GroupBackground
                                lastSpeaker?.isVoice2 == true -> SpeakerEntity.Voice2Background
                                else -> SpeakerEntity.Voice1Background
                            }
                        )
                    )
                    pos += 4
                    isBgSpeaker = true
                    continue
                }
                if (out.isEmpty() || out.last() is NewLine) {
                    val mmMatch = metadataRegex.matchAt(text, pos)
                    if (mmMatch != null) {
                        out.add(Metadata(mmMatch.groupValues[1], mmMatch.groupValues[2]))
                        pos += mmMatch.value.length
                        continue
                    }
                }
                // Word marks can be in any lyric text, and in some cases there are no sync points
                // but only word marks in a lrc file.
                val wmMatch = timeWordMarksRegex.matchAt(text, pos)
                if (wmMatch != null) {
                    out.add(WordSyncPoint(parseTime(wmMatch, false)))
                    pos += wmMatch.value.length
                    continue
                }
                val firstUnsafeCharPos = (text.substring(pos).indexOfFirst {
                    it == '[' ||
                            it == '<' || it == '\r' || it == '\n' || (isBgSpeaker && it == ']')
                } + pos)
                    .let { if (it == pos - 1) text.length else it }
                    .let { if (it == pos) it + 1 else it }
                val subText = text.substring(pos, firstUnsafeCharPos)
                val last = out.lastOrNull()
                // Only count lyric text as lyric text if there is at least one kind of timestamp
                // associated.
                if (out.indexOfLast { it is NewLine } <
                    out.indexOfLast { it is SyncPoint || it is WordSyncPoint }) {
                    if (last is LyricText) {
                        out[out.size - 1] = LyricText(last.text + subText)
                    } else {
                        out.add(LyricText(subText))
                    }
                } else {
                    if (last is InvalidText) {
                        out[out.size - 1] = InvalidText(last.text + subText)
                    } else {
                        out.add(InvalidText(subText))
                    }
                }
                pos = firstUnsafeCharPos
            }
            if (out.lastOrNull() is SyncPoint || out.lastOrNull() is LineEndSyncPoint)
                out.add(InvalidText(""))
            if (out.isNotEmpty() && out.last() !is NewLine)
                out.add(NewLine.SyntheticNewLine())
            out.let {
                // If there isn't a single sync point with timestamp over zero, that is probably not
                // a valid .lrc file.
                if (it.find {
                        it is SyncPoint && it.timestamp > 0u
                                || it is WordSyncPoint && it.timestamp > 0u
                                || it is LineEndSyncPoint && it.timestamp > 0u
                    } == null)
                // Recover only text information to make the most out of this damaged file.
                    return it.flatMap {
                        when (it) {
                            is InvalidText -> listOf(it)
                            is NewLine -> listOf(it)
                            is SpeakerTag -> listOf(it)
                            is LyricText -> listOf(InvalidText(it.text))
                            else -> listOf()
                        }
                    }
            }
            return out.let {
                if (multiLineEnabled) {
                    val a = AtomicReference<String?>(null)
                    it.flatMap {
                        val aa = a.get()
                        when {
                            it is LyricText -> {
                                if (aa == null)
                                    a.set(it.text)
                                else
                                    a.set(aa + it.text)
                                listOf()
                            }
                            // make sure InvalidText that can't be lyric text isn't saved as lyric
                            it is InvalidText && aa != null -> {
                                a.set(aa + it.text)
                                listOf()
                            }

                            it is NewLine && aa != null -> {
                                a.set(aa + "\n")
                                listOf()
                            }

                            aa != null -> {
                                a.set(null)
                                var aaa: String = aa
                                var i = 0
                                while (aaa.last() == '\n') {
                                    i++
                                    aaa = aaa.dropLast(1)
                                }
                                listOf(LyricText(aaa)).let {
                                    var aaaa: List<SyntacticLrc> = it
                                    while (i-- > 0)
                                        aaaa = aaaa + listOf(NewLine())
                                    aaaa
                                } + it
                            }

                            else -> listOf(it)
                        }
                    }.let {
                        if (a.get() != null)
                            it + if (a.get()!!.last() == '\n')
                                listOf(LyricText(a.get()!!.dropLast(1)), NewLine())
                            else
                                listOf(LyricText(a.get()!!))
                        else it
                    }
                } else it
            }
        }
    }
}

private fun splitBidirectionalWords(syncedLyrics: SyncedLyrics) {
    syncedLyrics.text.forEach { line ->
        if (line.words.isNullOrEmpty()) return@forEach
        val bidirectionalBarriers = findBidirectionalBarriers(line.text)
        var lastWasRtl = false
        bidirectionalBarriers.forEach { barrier ->
            val evilWordIndex =
                if (barrier.first == -1) -1 else line.words.indexOfFirst {
                    it.charRange.contains(barrier.first) && it.charRange.first != barrier.first
                }
            if (evilWordIndex == -1) {
                // Propagate the new direction (if there is a barrier after that, direction will
                // be corrected after it).
                val wordIndex = if (barrier.first == -1) 0 else
                    line.words.indexOfFirst { it.charRange.first == barrier.first }
                line.words.drop(wordIndex.coerceAtLeast(0)).forEach {
                    it.isRtl = barrier.second
                }
                lastWasRtl = barrier.second
                return@forEach
            }
            val evilWord = line.words[evilWordIndex]
            // Estimate how long this word will take based on character to time ratio. To avoid
            // this estimation, add a word sync point to bidirectional barriers :)
            val barrierTime = min(evilWord.timeRange.first + ((line.words.map {
                it.timeRange.count() / it.charRange.count().toFloat()
            }.average().let { if (it.isNaN()) 100.0 else it } * (barrier.first -
                    evilWord.charRange.first))).toULong(), evilWord.timeRange.last - 1uL)
            val firstPart = Word(
                charRange = evilWord.charRange.first..<barrier.first,
                timeRange = evilWord.timeRange.first..<barrierTime, isRtl = lastWasRtl
            )
            val secondPart = Word(
                charRange = barrier.first..evilWord.charRange.last,
                timeRange = barrierTime..evilWord.timeRange.last, isRtl = barrier.second
            )
            line.words[evilWordIndex] = firstPart
            line.words.add(evilWordIndex + 1, secondPart)
            lastWasRtl = barrier.second
        }
    }
}

private val ltr =
    arrayOf(
        Character.DIRECTIONALITY_LEFT_TO_RIGHT,
        Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
        Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE
    )
private val rtl =
    arrayOf(
        Character.DIRECTIONALITY_RIGHT_TO_LEFT,
        Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
        Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
        Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE
    )

fun findBidirectionalBarriers(text: CharSequence): List<Pair<Int, Boolean>> {
    val barriers = mutableListOf<Pair<Int, Boolean>>()
    if (text.isEmpty()) return barriers
    var previousDirection = text.find {
        val dir = Character.getDirectionality(it)
        dir in ltr || dir in rtl
    }?.let { Character.getDirectionality(it) in rtl } == true
    barriers.add(Pair(-1, previousDirection))
    for (i in 0 until text.length) {
        val currentDirection = Character.getDirectionality(text[i])
        val isRtl = currentDirection in rtl
        if (currentDirection !in ltr && !isRtl)
            continue
        if (previousDirection != isRtl)
            barriers.add(Pair(i, isRtl))
        previousDirection = isRtl
    }
    return barriers
}


/*
 * Syntactic lyric parser. Parse custom objects into usable representation for playback.
 *
 * Formats we have to consider in this component are:
 *  - Simple LRC files (ref Wikipedia) ex: [00:11.22] hello i am lyric
 *  - "compressed LRC" with >1 tag for repeating line ex: [00:11.22][00:15.33] hello i am lyric
 *  - Lyrics that aren't synced and have no tags at all
 *  - Translations, type 1 (ex: pasting first japanese and then english lrc file into one file)
 *     - This implies multiple [offset:] tags are possible
 *  - Translations, type 2 (ex: translated line directly under previous non-translated line)
 *  - Extended LRC (ref Wikipedia) ex: [00:11.22] <00:11.22> hello <00:12.85> i am <00:13.23> lyric
 *  - Extended LRC without sync points ex: <00:11.22> hello <00:12.85> i am <00:13.23> lyric
 *  - iTunes dual speaker extension (v1: / v2: / [bg: ])
 *  - [offset:] tag in header (ref Wikipedia)
 * We completely ignore all ID3 tags from the header as MediaStore is our source of truth.
 */
sealed class SemanticLyrics {
    abstract val unsyncedText: List<Pair<String, SpeakerEntity?>>

    data class UnsyncedLyrics(override val unsyncedText: List<Pair<String, SpeakerEntity?>>) :
        SemanticLyrics()

    data class SyncedLyrics(val text: List<LyricLine>) : SemanticLyrics() {
        override val unsyncedText
            get() = text.map { it.text to it.speaker }
    }

    data class LyricLine(
        val text: String,
        val start: ULong,
        var end: ULong,
        var endIsImplicit: Boolean,
        val words: MutableList<Word>?,
        var speaker: SpeakerEntity?,
        var isTranslated: Boolean
    ) {
        val isClickable: Boolean
            get() = text.isNotBlank()
        val timeRange: ULongRange
            get() = start..end
    }

    data class Word(
        var begin: ULong,
        var endInclusive: ULong?,
        var charRange: IntRange,
        var isRtl: Boolean
    ) {
        constructor(timeRange: ULongRange, charRange: IntRange, isRtl: Boolean)
                : this(timeRange.first, timeRange.last, charRange, isRtl)

        val timeRange
            get() = begin..endInclusive!!
    }
}

fun parseLrc(lyricText: String, trimEnabled: Boolean, multiLineEnabled: Boolean): SemanticLyrics? {
    val lyricSyntax = SyntacticLrc.parseLrc(lyricText, multiLineEnabled)
        ?: return null
    if (lyricSyntax.find { it is SyntacticLrc.SyncPoint || it is SyntacticLrc.WordSyncPoint } == null) {
        val out = mutableListOf<Pair<String?, SpeakerEntity?>>()
        var emptyIsEnd = false
        var shouldStartNewLine = false
        for (element in lyricSyntax) {
            when (element) {
                is SyntacticLrc.Metadata if element.name == "emptyIsEnd" -> {
                    emptyIsEnd = element.value == "1"
                }

                is SyntacticLrc.SpeakerTag -> {
                    if (out.lastOrNull()?.let { it.first == null } == true && !shouldStartNewLine)
                        out[out.size - 1] = null to element.speaker
                    else
                        out += null to element.speaker
                    shouldStartNewLine = false
                }

                is SyntacticLrc.NewLine.SyntheticNewLine -> {
                    shouldStartNewLine = true
                }

                is SyntacticLrc.NewLine -> {
                    if (!emptyIsEnd || out.lastOrNull()?.let { it.first == null } != true)
                        out += null to null
                    else
                        out[out.size - 1] = null to null
                    shouldStartNewLine = false
                }

                is SyntacticLrc.InvalidText -> {
                    if (out.isEmpty() || shouldStartNewLine)
                        out += null to null
                    shouldStartNewLine = false
                    out[out.size - 1] = (out.last().first ?: "") + element.text to out.last().second
                }

                else -> throw IllegalStateException("unexpected type ${element.javaClass.name}")
            }
        }
        val out2 = out.map { if (it.first == null) "" to it.second else
            @Suppress("UNCHECKED_CAST") (it as Pair<String, SpeakerEntity?>) }
            .toMutableList()
        while (out2.firstOrNull()?.first?.isBlank() == true)
            out2.removeAt(0)
        //while (out2.lastOrNull()?.first?.isBlank() == true)
        //    out2.removeAt(out.lastIndex) TODO this breaks unit tests, but blank lines are useless
        return UnsyncedLyrics(out2)
    }
    // Synced lyrics processing state machine starts here
    val out = mutableListOf<LyricLine>()
    var offset = 0L
    var lastSyncPoint: ULong? = null
    var lastWordSyncPoint: ULong? = null
    var pendingLineEndSyncPoint: ULong? = null
    var speaker: SpeakerEntity? = null
    var hadVoice2 = false
    var hadLyricSinceWordSync = true
    var hadWordSyncSinceNewLine = false
    var emptyIsEnd = false
    val currentLine = mutableListOf<Pair<ULong, String?>>()
    var syncPointStreak = 0
    val compressed = mutableListOf<ULong>()
    val compressedEnds = mutableListOf<ULong>()
    for (element in lyricSyntax) {
        if (syncPointStreak > 1 && element !is SyntacticLrc.LineEndSyncPoint &&
            compressedEnds.isNotEmpty() && compressed.size == compressedEnds.size + 1) {
            compressedEnds.add(compressed.last() + compressed[compressed.size - 2] -
                    compressedEnds.last())
        }
        if (element is SyntacticLrc.SyncPoint)
            syncPointStreak++
        else if (element !is SyntacticLrc.LineEndSyncPoint)
            syncPointStreak = 0
        when (element) {
            is SyntacticLrc.Metadata if element.name == "offset" -> {
                // positive offset means lyric played earlier in lrc, hence multiply with -1
                offset = element.value.toLong() * -1
            }

            is SyntacticLrc.Metadata if element.name == "emptyIsEnd" -> {
                emptyIsEnd = element.value == "1"
            }

            is SyntacticLrc.SyncPoint -> {
                val ts = (element.timestamp.toLong() + offset).coerceAtLeast(0).toULong()
                if (syncPointStreak > 1) {
                    compressed.add(ts)
                } else {
                    if (compressed.isNotEmpty())
                        throw IllegalStateException("while parsing lrc, $compressed not empty but syncPointStreak is 1; lrc file: $lyricText")
                    lastSyncPoint = ts
                }
            }

            is SyntacticLrc.LineEndSyncPoint -> {
                val ts = (element.timestamp.toLong() + offset).coerceAtLeast(0).toULong()
                if (syncPointStreak > 1) {
                    compressedEnds.add(ts)
                    if (compressedEnds.size != syncPointStreak - 1) {
                        throw IllegalStateException("while parsing lrc, compressedEnds too small: $compressedEnds, ${syncPointStreak - 1}, $lyricText")
                    }
                } else {
                    if (compressedEnds.isNotEmpty())
                        throw IllegalStateException("while parsing lrc, $compressedEnds not empty but syncPointStreak is 1; lrc file: $lyricText")
                    pendingLineEndSyncPoint = ts
                }
            }

            is SyntacticLrc.SpeakerTag -> {
                speaker = element.speaker
                if (element.speaker.isVoice2) {
                    hadVoice2 = true
                }
            }

            is SyntacticLrc.WordSyncPoint -> {
                if (!hadLyricSinceWordSync && lastWordSyncPoint != null)
                // add a dummy word for preserving end timing of previous word
                    currentLine.add(Pair(lastWordSyncPoint, null))
                lastWordSyncPoint = (element.timestamp.toLong() + offset).coerceAtLeast(0).toULong()
                if (lastSyncPoint == null)
                    lastSyncPoint = lastWordSyncPoint
                hadLyricSinceWordSync = false
                hadWordSyncSinceNewLine = true
            }

            is SyntacticLrc.LyricText -> {
                hadLyricSinceWordSync = true
                currentLine.add(Pair(lastWordSyncPoint ?: lastSyncPoint!!, element.text))
            }

            is SyntacticLrc.NewLine -> {
                val words = if (currentLine.size > 1 || hadWordSyncSinceNewLine) {
                    val wout = mutableListOf<Word>()
                    var idx = 0
                    for (i in currentLine.indices) {
                        val current = currentLine[i]
                        if (current.second == null)
                            continue // skip dummy words that only exist to provide time
                        val oIdx = idx
                        idx += current.second!!.length
                        // Make sure we do NOT include whitespace as part of the word. Whitespaces
                        // do not have a strong bi-di flag assigned and hence ICU4J/AndroidBidi will
                        // set bi-di transition point before whitespace. Rendering relies on being
                        // able to change edge treatment with trailing flag in Layout which only
                        // works on bi-di transition points. Additionally, excluding whitespace
                        // allows us to scale gradient properly based on asking ourselves if the
                        // next char is even rendered (or whitespace).
                        val textWithoutStartWhitespace = current.second!!.trimStart()
                        val startWhitespaceLength =
                            current.second!!.length - textWithoutStartWhitespace.length
                        val textWithoutWhitespaces = textWithoutStartWhitespace.trimEnd()
                        val endWhitespaceLength =
                            textWithoutStartWhitespace.length - textWithoutWhitespaces.length
                        val startIndex = oIdx + startWhitespaceLength
                        val endIndex = idx - endWhitespaceLength
                        if (startIndex == endIndex)
                            continue // word contained only whitespace
                        val endInclusive = if (i + 1 < currentLine.size) {
                            // If we have a next word (with sync point), use its sync
                            // point minus 1ms as end point of this word
                            currentLine[i + 1].first - 1uL
                        } else if (lastWordSyncPoint != null &&
                            lastWordSyncPoint > current.first
                        ) {
                            // If we have a dedicated sync point just for the last word,
                            // use it. Similar to dummy words but for the last word only
                            lastWordSyncPoint - 1uL // minus 1ms for consistency
                        } else if (pendingLineEndSyncPoint != null &&
                            pendingLineEndSyncPoint > current.first
                        ) {
                            // If we know when the line ends, use that for last word
                            pendingLineEndSyncPoint - 1uL // minus 1ms for consistency
                        } else null /* filled later */
                        if (endInclusive == null || endInclusive > current.first)
                        // isRtl is filled in later in splitBidirectionalWords
                            wout.add(
                                Word(
                                    current.first, endInclusive,
                                    startIndex..<endIndex,
                                    isRtl = false
                                )
                            )
                    }
                    wout
                } else null
                var text = currentLine.joinToString("") { it.second ?: "" }
                if (text.isNotBlank() || !emptyIsEnd &&
                    (lastWordSyncPoint != null || lastSyncPoint != null)) {
                    if (trimEnabled) {
                        val orig = text
                        text = orig.trimStart()
                        val startDiff = orig.length - text.length
                        text = text.trimEnd()
                        val iter = words?.listIterator()
                        iter?.forEach {
                            if (it.charRange.last.toLong() - startDiff < 0
                                || it.charRange.first.toLong() - startDiff >= text.length
                            )
                                iter.remove()
                            else
                                it.charRange = (it.charRange.first - startDiff)
                                    .coerceAtLeast(0)..(it.charRange.last - startDiff)
                                    .coerceAtMost(text.length - 1)
                        }
                    }
                    val start = if (currentLine.isNotEmpty()) lastSyncPoint
                        ?: currentLine.first().first
                    else lastWordSyncPoint ?: lastSyncPoint!!
                    // if we had trailing word sync point after the last word was already ended,
                    // it's an explicit line end ts, so use it. otherwise, use the last word's sync
                    // point as end time. otherwise we will fill it later based on next (temporal,
                    // not file order) line.
                    val lastWordSyncForEnd = lastWordSyncPoint?.let { it - 1uL }
                    val lastWordBegin = words?.lastOrNull()?.begin
                    val end = pendingLineEndSyncPoint?.also { pendingLineEndSyncPoint = null }
                        ?: if (lastWordSyncForEnd != null && lastWordBegin != null &&
                            lastWordBegin < lastWordSyncForEnd) lastWordSyncForEnd else null
                    out.add(
                        LyricLine(
                            text, start, end ?: 0uL /* filled later */,
                            end == null, words, speaker, false /* filled later */
                        )
                    )
                    compressed.forEachIndexed { i, it ->
                        val diff = it - start
                        out.add(
                            out.last().copy(
                                start = it,
                            end = end?.plus(diff) ?: 0uL /* filled later */,
                            words = words?.map {
                                it.copy(
                                    begin = it.begin + diff,
                                    endInclusive = if (compressedEnds.isNotEmpty())
                                        compressedEnds[i] else it.endInclusive?.plus(diff)
                                )
                            }?.toMutableList()
                        )
                        )
                    }
                } else if (lastWordSyncPoint != null || lastSyncPoint != null) {
                    out.lastOrNull()?.let {
                        out[out.size - 1] = it.copy(end = lastWordSyncPoint ?: lastSyncPoint!!,
                            endIsImplicit = false)
                        if (it.words?.lastOrNull()?.let { w -> w.endInclusive == null } == true) {
                            it.words.last().endInclusive = lastWordSyncPoint ?: lastSyncPoint!!
                        }
                    }
                }
                compressed.clear()
                compressedEnds.clear()
                currentLine.clear()
                lastSyncPoint = null
                lastWordSyncPoint = null
                hadWordSyncSinceNewLine = false
                speaker = null
                hadLyricSinceWordSync = true
            }

            else -> {}
        }
    }
    out.sortBy { it.start }
    var previousLyric: LyricLine? = null
    out.forEach { lyric ->
        if (!hadVoice2) { // If there is no v2: tag, we should avoid width limit.
            lyric.speaker = when (lyric.speaker) {
                SpeakerEntity.Voice1 -> SpeakerEntity.Voice
                SpeakerEntity.Voice1Background -> SpeakerEntity.VoiceBackground
                else -> lyric.speaker
            }
        }
        val mainEnd = if (lyric.start == previousLyric?.start) out.firstOrNull {
            it.start == lyric.start && !it.endIsImplicit
        }?.end else null
        val wordWithoutEnd = lyric.words?.lastOrNull()
        if (wordWithoutEnd != null && wordWithoutEnd.endInclusive == null) {
            wordWithoutEnd.endInclusive = mainEnd?.takeIf { it > wordWithoutEnd.begin }
                ?: out.find { it.start > lyric.start }?.start?.minus(1uL)
                    ?.takeIf { it > wordWithoutEnd.begin }
                        ?: run {
                    // Estimate how long this word will take based on character
                    // to time ratio. To avoid this estimation, add a last word
                    // sync point to the line after the text :)
                    wordWithoutEnd.begin + (lyric.words.subList(0, lyric.words.size - 1)
                        .map { it.timeRange.count() / it.charRange.count().toFloat() }
                        .average().let { if (it.isNaN()) 100.0 else it } *
                            lyric.text.substring(wordWithoutEnd.charRange).length).toULong()
                }
        }
        if (lyric.endIsImplicit) {
            if (mainEnd != null) {
                lyric.end = mainEnd
                lyric.endIsImplicit = false
            } else {
                lyric.end = wordWithoutEnd?.endInclusive
                    ?: out.find { it.start > lyric.start }?.start?.minus(1uL)
                            ?: Long.MAX_VALUE.toULong()
                lyric.endIsImplicit = wordWithoutEnd == null // TODO: should this just stay true?
            }
        }
        lyric.isTranslated = lyric.start == previousLyric?.start &&
                (previousLyric.text.isNotBlank() || lyric.text.isBlank())
        previousLyric = lyric
    }
    while (out.firstOrNull()?.text?.isBlank() == true)
        out.removeAt(0)
    //while (out.lastOrNull()?.text?.isBlank() == true)
    //    out.removeAt(out.lastIndex) TODO this breaks unit tests, but blank lines are useless
    return SyncedLyrics(out).also { splitBidirectionalWords(it) }
}

// Class heavily based on MIT-licensed https://github.com/yoheimuta/ExoPlayerMusic/blob/77cfb989b59f6906b1170c9b2d565f9b8447db41/app/src/main/java/com/github/yoheimuta/amplayer/playback/UsltFrameDecoder.kt

/** The lines of an ID3 SYLT frame; the frame header (encoding, language, description) is read by jaudiotagger. */
class SyltLyrics(val text: List<Line>) {
    data class Line(val timestamp: UInt, val text: String)

    // Based on Gramophone's UsltFrameDecoder.decodeSylt, which is based on ExoPlayer's Id3Decoder.
    companion object {
        private const val ID3_TEXT_ENCODING_ISO_8859_1 = 0
        private const val ID3_TEXT_ENCODING_UTF_16 = 1
        private const val ID3_TEXT_ENCODING_UTF_16BE = 2
        private const val ID3_TEXT_ENCODING_UTF_8 = 3

        /** [data] is the frame content after the description: text, terminator, 4-byte timestamp, repeated. */
        fun decode(encoding: Int, timestampFormat: Int, sampleRate: Int, data: ByteArray): SyltLyrics? {
            val charset = getCharsetName(encoding) ?: return null
            var processed = 0
            val syltLines = mutableListOf<Line>()
            while (data.size - processed > 1) {
                val textEndIndex = indexOfEos(data, processed, encoding)
                val text = decodeStringIfValid(data, processed, textEndIndex, charset)
                processed = textEndIndex + delimiterLength(encoding) + 4
                if (processed > data.size) {
                    // Frame is malformed.
                    return null
                }
                val timestamp = decodeTimestamp(data, processed - 4, timestampFormat, sampleRate) ?: return null
                syltLines.add(Line(timestamp, text))
            }
            return SyltLyrics(syltLines)
        }

        private fun getCharsetName(encodingByte: Int): Charset? =
            when (encodingByte) {
                ID3_TEXT_ENCODING_UTF_16 -> Charsets.UTF_16
                ID3_TEXT_ENCODING_UTF_16BE -> Charsets.UTF_16BE
                ID3_TEXT_ENCODING_UTF_8 -> Charsets.UTF_8
                ID3_TEXT_ENCODING_ISO_8859_1 -> Charsets.ISO_8859_1
                else -> null
            }

        private fun indexOfEos(data: ByteArray, fromIndex: Int, encoding: Int): Int {
            var terminationPos = indexOfZeroByte(data, fromIndex)

            // For single byte encoding charsets, we're done.
            if (encoding == ID3_TEXT_ENCODING_ISO_8859_1 || encoding == ID3_TEXT_ENCODING_UTF_8) {
                return terminationPos
            }

            // Otherwise ensure an even index (from the string start) and look for a second zero byte.
            while (terminationPos < data.size - 1) {
                if ((terminationPos - fromIndex) % 2 == 0 && data[terminationPos + 1] == 0.toByte()) {
                    return terminationPos
                }
                terminationPos = indexOfZeroByte(data, terminationPos + 1)
            }

            return data.size
        }

        private fun indexOfZeroByte(data: ByteArray, fromIndex: Int): Int {
            for (i in fromIndex until data.size) {
                if (data[i] == 0.toByte()) {
                    return i
                }
            }
            return data.size
        }

        private fun delimiterLength(encodingByte: Int): Int =
            if (encodingByte == ID3_TEXT_ENCODING_ISO_8859_1 || encodingByte == ID3_TEXT_ENCODING_UTF_8) 1 else 2

        private fun decodeStringIfValid(data: ByteArray, from: Int, to: Int, charset: Charset): String =
            if (to <= from || to > data.size) "" else String(data, from, to - from, charset)

        private fun decodeTimestamp(data: ByteArray, pos: Int, format: Int, sampleRate: Int): UInt? {
            val dec =
                ((((data[pos].toUInt() shl 24) and 0xff000000U) or ((data[pos + 1].toUInt() shl 16)
                        and 0xff0000U)) or ((data[pos + 2].toUInt() shl 8) and 0xff00U)) or (data[pos + 3].toUInt() and 0xffU)
            return when (format) {
                1 -> mpegFramePositionToMs(sampleRate, dec.toLong())?.toUInt()
                2 -> dec
                else -> null
            }
        }

        private fun mpegFramePositionToMs(sampleRate: Int, pos: Long): Long? {
            val samplesPerFrame = when (sampleRate) {
                32000, 44100, 48000 -> 1152 // MPEG-1
                16000, 22050, 24000 -> 576 // MPEG-2
                8000, 11025, 12000 -> 576 // MPEG-2.5
                else -> return null
            }
            return (pos * samplesPerFrame * 1000L) / sampleRate
        }
    }
}

fun SyltLyrics.toSyncedLyrics(trimEnabled: Boolean): SyncedLyrics {
    val out = mutableListOf<LyricLine>()
    var i = 0
    while (i < text.size) {
        var j = i + 1
        // ID3 spec: SYLT whitespace is in next elem, not this one, and that also includes
        // newlines.
        while (j < text.size &&
            !text[j].text.trimStart { it == '\t' || it == ' ' || it == '\r' }.startsWith("\n")
        ) {
            j++ // find the next line start, so that j points to last element (exclusive)
        }
        var idx = 0
        val wout = mutableListOf<Word>()
        for (k in i..<j) {
            val it = text[k]
            val next = if (k + 1 < j) text[k + 1] else null
            val oIdx = idx
            idx += it.text.length
            // Make sure we do NOT include whitespace as part of the word. Whitespaces
            // do not have a strong bi-di flag assigned and hence ICU4J/AndroidBidi will
            // set bi-di transition point before whitespace. Rendering relies on being
            // able to change edge treatment with trailing flag in Layout which only
            // works on bi-di transition points. Additionally, excluding whitespace
            // allows us to scale gradient properly based on asking ourselves if the
            // next char is even rendered (or whitespace).
            val textWithoutStartWhitespace = it.text.trimStart()
            val startWhitespaceLength =
                it.text.length - textWithoutStartWhitespace.length
            val textWithoutWhitespaces = textWithoutStartWhitespace.trimEnd()
            val endWhitespaceLength =
                textWithoutStartWhitespace.length - textWithoutWhitespaces.length
            val startIndex = oIdx + startWhitespaceLength
            val endIndex = idx - endWhitespaceLength
            if (startIndex == endIndex)
                continue // word contained only whitespace
            val endInclusive = if (next != null && next.timestamp > 0uL) {
                // If we have a next word (with sync point), use its sync
                // point minus 1ms as end point of this word
                next.timestamp - 1uL
            } else null
            if (endInclusive == null || endInclusive > it.timestamp)
            // isRtl is filled in later in splitBidirectionalWords
                wout.add(
                    Word(
                        it.timestamp.toULong(), endInclusive,
                        startIndex..<endIndex,
                        isRtl = false
                    )
                )
        }
        var string = text.subList(i, j).joinToString("") { it.text }
        val nli1 = string.indexOf('\n')
        if (nli1 != -1 && string.take(nli1)
                .trimStart { it == '\t' || it == ' ' || it == '\r' }.isEmpty()
        ) {
            // remove last line's trailing whitespace (and eat newline)
            string = string.substring(nli1 + 1)
        }
        if (j < text.size) {
            // get our own trailing whitespace from next line (excl newline)
            var nli = text[j].text.indexOf('\n')
            if (nli == -1) {
                throw IllegalStateException("nli == -1, can't happen")
            }
            if (nli > 0 && text[j].text[nli - 1] == '\r')
                nli-- // don't split CRLF in half
            string += text[j].text.substring(0, nli)
        }
        if (trimEnabled) {
            val orig = string
            string = orig.trimStart()
            val startDiff = orig.length - string.length
            string = string.trimEnd()
            val iter = wout.listIterator()
            iter.forEach {
                if (it.charRange.last.toLong() - startDiff < 0
                    || it.charRange.first.toLong() - startDiff >= string.length
                )
                    iter.remove()
                else
                    it.charRange = (it.charRange.first - startDiff)
                        .coerceAtLeast(0)..(it.charRange.last - startDiff)
                        .coerceAtMost(string.length - 1)
            }
        }
        // if we had trailing sync point only with new line, it's explicit line end ts, use it.
        // if we have >1 segment, use it's last sync point (possibly estimated) as end time,
        // otherwise we will fill it later based on next line.
        val explicitEnd = if (i < j - 1 && text[j - 1].text.isBlank())
            text[j - 1].timestamp.toULong() - 1uL
        else if (wout.size > 1) wout.last().endInclusive else null
        out.add(
            LyricLine(
                string, text[i].timestamp.toULong(), explicitEnd ?: 0uL
                /* filled later */, explicitEnd == null,
                if (wout.size > 1) wout else null, null,
                false /* filled later */
            )
        )
        i = j
    }
    out.sortBy { it.start }
    var previousTimestamp = ULong.MAX_VALUE
    out.forEach { lyric ->
        val mainEnd = if (lyric.start == previousTimestamp) out.firstOrNull {
            it.start == lyric.start && !it.endIsImplicit
        }?.end else null
        val wordWithoutEnd = lyric.words?.lastOrNull()
        if (wordWithoutEnd != null && wordWithoutEnd.endInclusive == null) {
            wordWithoutEnd.endInclusive = mainEnd?.takeIf { it > wordWithoutEnd.begin }
                ?: out.find { it.start > lyric.start }?.start?.minus(1uL)
                    ?.takeIf { it > wordWithoutEnd.begin }
                        ?: run {
                    // Estimate how long this word will take based on character
                    // to time ratio. To avoid this estimation, add a last word
                    // sync point to the line after the text :)
                    wordWithoutEnd.begin + (lyric.words.subList(0, lyric.words.size - 1)
                        .map { it.timeRange.count() / it.charRange.count().toFloat() }
                        .average().let { if (it.isNaN()) 100.0 else it } *
                            lyric.text.substring(wordWithoutEnd.charRange).length).toULong()
                }
        }
        if (lyric.endIsImplicit) {
            if (mainEnd != null) {
                lyric.end = mainEnd
                lyric.endIsImplicit = false
            } else {
                lyric.end = wordWithoutEnd?.endInclusive
                    ?: out.find { it.start > lyric.start }?.start?.minus(1uL)
                            ?: Long.MAX_VALUE.toULong()
                lyric.endIsImplicit = wordWithoutEnd == null // TODO: should this just stay true?
            }
        }
        lyric.isTranslated = lyric.start == previousTimestamp
        previousTimestamp = lyric.start
    }
    while (out.firstOrNull()?.text?.isBlank() == true)
        out.removeAt(0)
    //while (out.lastOrNull()?.text?.isBlank() == true)
    //    out.removeAt(out.lastIndex) TODO this breaks (lrc's) unit tests, but blank lines are useless
    return SyncedLyrics(out).also { splitBidirectionalWords(it) }
}