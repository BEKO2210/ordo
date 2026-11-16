/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

/** What a net design (Guarded, Tuned) answers for one command, in the app's terms. */
sealed interface NetOutcome {
    /**
     * A plan for the usual checks, preview or result list. [warnings]: rules2's count check; [dropped]: words left out
     * because together with the others they found nothing. Both are said in the preview or under the list.
     */
    data class Plan(val plan: CommandPlan, val warnings: List<String> = emptyList(), val dropped: List<String> = emptyList()) :
        NetOutcome

    /** The net is unsure and asks back; [args] fill the question (a folder, a word). The typed command stays. */
    data class Ask(val reason: String, val args: List<String>) : NetOutcome

    /** Something Ordo cannot do ([reason]: archive, convert, rename, share, open, restore, edit) or cannot know (memory). */
    data class Refuse(val reason: String) : NetOutcome
}

/** Turns the model's answer and the net's decision into the app's plan. */
internal object CommandNetPlan {
    /** The model's JSON answer as the map the net reads; null when it is no JSON object (the lab's `_bad` answer). */
    fun raw(answer: String?): Map<String, Any?>? = answer?.let {
        try {
            json(JSONObject(it))
        } catch (e: Exception) {
            null
        }
    }

    // Android's org.json has no keySet()
    fun json(o: JSONObject): Map<String, Any?> = o.keys().asSequence().toList().associateWith { value(o.get(it)) }

    fun value(v: Any?): Any? = when (v) {
        JSONObject.NULL, null -> null
        is JSONObject -> json(v)
        is JSONArray -> (0 until v.length()).map { value(v.get(it)) }
        is Number -> if (v.toDouble() == Math.floor(v.toDouble())) v.toLong() else v.toDouble()
        else -> v
    }

    /** A model answer that is no JSON is "none" in the lab (eval3: {"action": "none", "_bad": ...}). */
    private fun rawOrNone(answer: String?): Map<String, Any?> = raw(answer) ?: mapOf("action" to "none")

    fun guarded(text: String, answer: String?, folders: List<String>, now: LocalDateTime, count: (CommandPlan) -> Int) =
        outcome(CommandNetDecide.guarded(text, answer?.let { rawOrNone(it) }, folders, now) { count(plan(it)) })

    fun tuned(text: String, answer: String?, folders: List<String>, now: LocalDateTime) =
        outcome(CommandNetDecide.tuned(text, answer?.let { rawOrNone(it) }, folders, now))

    private fun outcome(d: NetDecision): NetOutcome = when (d.status) {
        "refuse" -> NetOutcome.Refuse(d.reason)
        "ask" -> NetOutcome.Ask(d.reason, d.args)
        else -> NetOutcome.Plan(plan(d.plan!!), d.warn, d.dropped)
    }

    /**
     * A filter word as the user typed it, for everything shown: the net keeps folded stems ("steuererklarung"), the user
     * wrote "Steuererklärung". The first word of [text] with that stem; else the part of a compound that is the word
     * ("Rechnung" from "Handyrechnung", never the whole compound, which would read narrower than the filter), with the
     * user's umlauts and a capital when the compound had one; else the stem itself. Display only; the plan keeps the stem.
     */
    fun typed(text: String, term: String): String {
        val words = Regex("[\\p{L}\\p{N}]+").findAll(text).map { it.value }.toList()
        val folded = CommandNet.fold(term)
        words.firstOrNull { CommandNet.stem(it) == folded || CommandNet.fold(it) == folded }?.let { return it }
        if (folded.length < 3) return term
        for (w in words) {
            // the tail of the compound with that stem ("rechnungen" in "Stromrechnungen"), else the shortest part
            val part = (1 until w.length).map { w.substring(it) }.firstOrNull { CommandNet.stem(it) == folded }
                ?: (0 until w.length).asSequence().flatMap { i -> (i + 1..w.length).asSequence().map { j -> w.substring(i, j) } }
                    .firstOrNull { CommandNet.fold(it) == folded }
                ?: continue
            return if (w[0].isUpperCase() && part[0].isLowerCase()) part.replaceFirstChar { it.titlecase() } else part
        }
        return term
    }

    @Suppress("UNCHECKED_CAST")
    fun plan(p: Map<String, Any>): CommandPlan {
        fun s(k: String) = p[k] as? String ?: ""
        fun l(k: String) = p[k] as? List<String> ?: emptyList()
        fun n(k: String) = (p[k] as? Number)?.toLong() ?: 0L
        // The lab's executor reads these words for the whole phone (phase0.execute); a model may write them for "show".
        val source = s("source").let { if (it.trim().lowercase() in setOf("handy", "telefon", "phone")) "" else it }
        return CommandPlan(
            action = s("action"), source = source, destination = s("destination"), kinds = l("kinds"),
            extensions = l("extensions"), sort = s("sort"), limit = n("limit").coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            groupBy = s("group_by"), excludeKinds = l("exclude_kinds"), excludeExtensions = l("exclude_extensions"),
            excludeTerms = l("exclude_terms"), dateFrom = s("date_from"), dateTo = s("date_to"), minBytes = n("min_bytes"),
            maxBytes = n("max_bytes"), nameTerms = l("name_terms"), textTerms = l("text_terms")
        )
    }
}
