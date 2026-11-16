/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import android.os.Bundle
import android.util.Log
import org.json.JSONObject
import me.zhanghai.android.files.app.AppActivity

/**
 * Debug builds only (app/src/debug). Opens the command sheet with a model answer given from outside, so the rules,
 * checks, result list, hints and confirmations can be tested on a device that cannot run the model:
 *
 *   adb shell am start -n com.beko2210.ordo/me.zhanghai.android.files.ai.DebugAiActivity \
 *       --es text "zeig mir die videos über 500 mb" --es answer '{"action":"show","kinds":["video"]}'
 *
 * `--ez show_design true|false` instead switches the K+R design with "show" on or off for real model commands.
 * `--es adapter "<file name>,<size>,<sha256>"` gives the current design a LoRA adapter in the model folder (checked by
 * size and hash like the model); `--es adapter ""` removes it again.
 * `--es golden <file in the app's external files dir>` runs CommandRulesGoldenTest's fixture through the rules on
 * the device (Android's regex engine is ICU, not the JVM's) and logs "OrdoGolden: n/m equal".
 */
class DebugAiActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.hasExtra(EXTRA_SHOW_DESIGN)) {
            CommandDesign.setShowEnabled(this, intent.getBooleanExtra(EXTRA_SHOW_DESIGN, false))
            finish()
            return
        }
        intent.getStringExtra(EXTRA_ADAPTER)?.let {
            CommandDesign.setAdapter(this, it)
            finish()
            return
        }
        intent.getStringExtra(EXTRA_GOLDEN)?.let { name ->
            val file = java.io.File(getExternalFilesDir(null), name)
            Thread { Log.i("OrdoGolden", golden(file.readText())) }.start()
            finish()
            return
        }
        if (savedInstanceState == null) {
            CommandSheetFragment.withModelAnswer(
                intent.getStringExtra(EXTRA_TEXT).orEmpty(), intent.getStringExtra(EXTRA_ANSWER) ?: "{}"
            ).show(supportFragmentManager, CommandSheetFragment.TAG)
        }
    }

    // Back from a result list or the analysis: this empty screen is not where the user was, so leave it too. (Not
    // when the sheet closes: its snackbar with the result and Undo lives here.)
    override fun onRestart() {
        super.onRestart()
        finish()
    }

    private fun golden(json: String): String {
        val o = JSONObject(json)
        val sets = o.getJSONArray("folders")
        val rows = o.getJSONArray("rows")
        var equal = 0
        val wrong = mutableListOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val text = row.getString("text")
            val folders = sets.getJSONArray(row.getInt("folders")).let { a -> (0 until a.length()).map { a.getString(it) } }
            val actual: Any = when (val route = CommandRules.route(text)) {
                is CommandRules.Route.Refuse -> "refuse"
                is CommandRules.Route.Screen -> route.plan
                null -> CommandRules.fix(text, CommandPlan.parse(row.getJSONObject("raw").toString())!!, folders)
            }
            val expected: Any = if (row.optBoolean("refuse")) "refuse"
            else CommandPlan.parse(row.getJSONObject("final").toString())!!
            if (actual == expected) equal++ else wrong += "${row.getString("variant")} ${row.getString("id")} $text"
        }
        return "$equal/${rows.length()} equal" + wrong.take(10).joinToString("") { "\n  differs: $it" }
    }

    private companion object {
        const val EXTRA_GOLDEN = "golden"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ANSWER = "answer"
        const val EXTRA_SHOW_DESIGN = "show_design"
        const val EXTRA_ADAPTER = "adapter"
    }
}
