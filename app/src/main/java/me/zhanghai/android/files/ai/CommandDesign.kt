/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import android.content.Context
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import java.time.LocalDateTime

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

    /**
     * The net designs decide the whole answer from the model's JSON [answer] (null: before the model, only a refusal
     * can come back) and the phone's folders; [count] is how many files a plan finds. Null for the older designs,
     * which use [route] and [fix].
     */
    fun decide(text: String, answer: String?, folders: List<String>, now: LocalDateTime, count: (CommandPlan) -> Int)
        : NetOutcome? = null

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

    /**
     * Prompt C + the frozen net rules2.decide (lab 430da086), with "show". Blind tests on the lab phone: 29/50 (blind 2)
     * and 45/60 (blind 3) correct, 0 dangerous; today's design 16/50 and 11/60 with 8 and 15 dangerous plans.
     * Users get it only when they turn on the preview in the settings (off by default).
     */
    object Guarded : CommandDesign {
        override val system = CommandNetLexicon.SYSTEM_C
        override val grammar = CommandNetLexicon.GRAMMAR_C
        override fun route(text: String): CommandRules.Route? = null
        override fun fix(text: String, plan: CommandPlan, folders: List<String>) = plan
        override fun decide(text: String, answer: String?, folders: List<String>, now: LocalDateTime, count: (CommandPlan) -> Int) =
            CommandNetPlan.guarded(text, answer, folders, now, count)
    }

    /**
     * The fine-tuned model (LoRA adapter, schema L, short prompt) + the frozen net rules3.decide (lab 13bfbed9). Debug
     * builds only: blind test 3 gave 44/60 (0 dangerous), below the 80 % the owner asked for; the adapter is not
     * published yet (a download answers 404, the sheet says so).
     */
    object Tuned : CommandDesign {
        override val system = CommandNetLexicon.SYSTEM_LORA
        override val grammar = CommandNetLexicon.GRAMMAR_L
        // The only part a retrained adapter changes (same prompt, grammar and net): file name, size, hash, address.
        override val adapter = CommandModel.Part(
            "ordo-commands-1.gguf", 42_485_408L, "30ac6052cf23a0052262f52c44dbd860533b164db33c599a76a674a2f84ede64",
            "https://github.com/BEKO2210/ordo/releases/download/ai-adapter-1/ordo-commands-1.gguf"
        )
        override fun route(text: String): CommandRules.Route? = null
        override fun fix(text: String, plan: CommandPlan, folders: List<String>) = plan
        override fun decide(text: String, answer: String?, folders: List<String>, now: LocalDateTime, count: (CommandPlan) -> Int) =
            CommandNetPlan.tuned(text, answer, folders, now)
    }

    /** A design with an adapter added; everything else as [base]. Debug builds only, until an adapter ships. */
    class WithAdapter(base: CommandDesign, override val adapter: CommandModel.Part) : CommandDesign by base

    companion object {
        private const val PREFS = "ai_debug"
        private const val KEY_SHOW = "show_design"
        private const val KEY_ADAPTER = "adapter"
        private const val KEY_DESIGN = "design"

        /**
         * The design for the next command: [Guarded] when the user turned on the preview (Settings › AI assistant,
         * off by default), else [Today]. Debug builds may override it through DebugAiActivity.
         */
        fun current(context: Context): CommandDesign {
            val preview = Settings.AI_PREVIEW.valueCompat
            // BuildConfig.DEBUG is a constant: in a release build the debug override is compiled out.
            if (!BuildConfig.DEBUG) return select(preview, null, false)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val base = select(preview, prefs.getString(KEY_DESIGN, null), prefs.getBoolean(KEY_SHOW, false))
            return prefs.getString(KEY_ADAPTER, null)?.let { parseAdapter(it) }?.let { WithAdapter(base, it) } ?: base
        }

        /** [pick] and [showKr]: the debug overrides (DebugAiActivity), null and false in a release build. */
        internal fun select(preview: Boolean, pick: String?, showKr: Boolean): CommandDesign = when (pick) {
            "today" -> Today
            "kr" -> KR
            "guarded" -> Guarded
            "tuned" -> Tuned
            else -> if (showKr) KR else if (preview) Guarded else Today
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
                .apply { if (spec.isEmpty()) remove(KEY_ADAPTER) else putString(KEY_ADAPTER, spec) }
                // commit: the activity finishes at once, and adb may stop the app right after (an apply would be lost).
                .commit()
        }

        /** Debug builds only (DebugAiActivity): today, kr, guarded or tuned; "" goes back to the default. */
        fun setDesign(context: Context, name: String) {
            if (!BuildConfig.DEBUG) return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .apply { if (name.isEmpty()) remove(KEY_DESIGN) else putString(KEY_DESIGN, name) }.commit()
        }

        /** Debug builds only (DebugAiActivity): turns K+R with "show" on or off for real model commands. */
        fun setShowEnabled(context: Context, enabled: Boolean) {
            if (!BuildConfig.DEBUG) return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOW, enabled).commit()
        }
    }
}
