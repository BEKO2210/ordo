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
    /** A plan for the usual checks, preview or result list. [warnings]: rules2's count check, for the log only. */
    data class Plan(val plan: CommandPlan, val warnings: List<String> = emptyList()) : NetOutcome

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

    private fun json(o: JSONObject): Map<String, Any?> = o.keySet().associateWith { value(o.get(it)) }

    private fun value(v: Any?): Any? = when (v) {
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
        else -> NetOutcome.Plan(plan(d.plan!!), d.warn)
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
