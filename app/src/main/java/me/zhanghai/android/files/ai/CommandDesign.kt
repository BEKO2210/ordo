/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import android.content.Context
import me.zhanghai.android.files.BuildConfig

/**
 * Prompt, grammar and word rules as one swappable unit, so a reworked design (docs/KI-ZEIGEN-PHASE0B.md, Phase 0c)
 * replaces only this. Everything after it (checks, preview, "show" list, runner) reads only the plan.
 */
interface CommandDesign {
    val system: String
    val grammar: String

    /** A LoRA adapter for the model, downloaded and checked with it; null for the base model alone. */
    val adapter: CommandModel.Part? get() = null

    /** An answer without the model (a refusal or an existing screen), or null to ask the model. */
    fun route(text: String): CommandRules.Route?

    /** The model's plan with what the words decide put right. */
    fun fix(text: String, plan: CommandPlan, folders: List<String>): CommandPlan

    /** The shipped design: move, copy, delete, organize; no rules, no "show". */
    object Today : CommandDesign {
        override val system = CommandPlanner.SYSTEM_TODAY
        override val grammar = CommandPlanner.GRAMMAR_TODAY
        override fun route(text: String): CommandRules.Route? = null
        override fun fix(text: String, plan: CommandPlan, folders: List<String>) = plan
    }

    /**
     * K+R with "show". Off for users: it reached 22/40 with 2 dangerous plans in the blind test
     * (tools/memory-lab/blind/results.md) and is being reworked. Only a debug build can switch it on.
     */
    object KR : CommandDesign {
        override val system = CommandPlanner.SYSTEM_K
        override val grammar = CommandPlanner.GRAMMAR_K
        override fun route(text: String) = CommandRules.route(text)
        override fun fix(text: String, plan: CommandPlan, folders: List<String>) = CommandRules.fix(text, plan, folders)
    }

    /** A design with an adapter added; everything else as [base]. Debug builds only, until an adapter ships. */
    class WithAdapter(base: CommandDesign, override val adapter: CommandModel.Part) : CommandDesign by base

    companion object {
        private const val PREFS = "ai_debug"
        private const val KEY_SHOW = "show_design"
        private const val KEY_ADAPTER = "adapter"

        // BuildConfig.DEBUG is a constant: in a release build this is always Today and the switch is compiled out.
        fun current(context: Context): CommandDesign {
            if (!BuildConfig.DEBUG) return Today
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val base = if (prefs.getBoolean(KEY_SHOW, false)) KR else Today
            return prefs.getString(KEY_ADAPTER, null)?.let { parseAdapter(it) }?.let { WithAdapter(base, it) } ?: base
        }

        /** "file name,size,sha256[,url]", as DebugAiActivity takes it; null when malformed. */
        internal fun parseAdapter(spec: String): CommandModel.Part? = spec.split(',').let { f ->
            if (f.size !in 3..4) null
            else runCatching { CommandModel.Part(f[0], f[1].toLong(), f[2], f.getOrElse(3) { "" }) }.getOrNull()
        }

        /** Debug builds only (DebugAiActivity): an adapter for the current design, or none with an empty [spec]. */
        fun setAdapter(context: Context, spec: String) {
            if (!BuildConfig.DEBUG) return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .apply { if (spec.isEmpty()) remove(KEY_ADAPTER) else putString(KEY_ADAPTER, spec) }.apply()
        }

        /** Debug builds only (DebugAiActivity): turns K+R with "show" on or off for real model commands. */
        fun setShowEnabled(context: Context, enabled: Boolean) {
            if (!BuildConfig.DEBUG) return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOW, enabled).apply()
        }
    }
}
