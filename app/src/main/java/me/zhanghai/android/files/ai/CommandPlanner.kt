/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Asks the model for a plan. Prompt and schema match utils/command-bench/bench.py, keep both in sync. */
object CommandPlanner {
    // shortcut: test builds ask a llama-server on the developer's PC (Qwen3.5-4B Q4_K_M); step 3 of
    // docs/KI-BEFEHLE.md replaces this with llama.cpp on the phone.
    private const val SERVER = "http://192.168.2.158:8086"

    suspend fun plan(text: String): CommandPlan? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM))
                .put(JSONObject().put("role", "user").put("content", text)))
            .put("temperature", 0)
            .put("max_tokens", 200)
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            .put("response_format", JSONObject().put("type", "json_schema")
                .put("json_schema", JSONObject().put("name", "plan").put("schema", JSONObject(SCHEMA))))
        val connection = URL("$SERVER/v1/chat/completions").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 120_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val answer = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            CommandPlan.parse(
                answer.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                    .getString("content")
            )
        } finally {
            connection.disconnect()
        }
    }

    private const val SCHEMA = """{"type":"object","properties":{
"action":{"enum":["move","copy","delete","organize","none"]},"source":{"type":"string"},
"destination":{"type":"string"},
"kinds":{"type":"array","items":{"enum":["audio","video","image","document","archive"]}},
"extensions":{"type":"array","items":{"type":"string"}},"name_contains":{"type":"string"},
"older_than":{"type":"integer"},"age_unit":{"enum":["days","weeks","months","years"]},
"larger_than":{"type":"integer"},"size_unit":{"enum":["mb","gb"]},"group_by":{"enum":["","type","month"]}},
"required":["action","source","destination","kinds","extensions","name_contains","older_than","age_unit",
"larger_than","size_unit","group_by"],"additionalProperties":false}"""

    private val SYSTEM = """You turn one command for a file manager into a JSON plan. The command is German or English.

Fields:
- action: move, copy, delete, organize (tidy up, sort into subfolders) or none.
  Use none for anything else: questions, greetings, rename, share, send, open, format, uninstall.
- source: the folder the files are in, as the user names it ("in X", "from X", "aus X", "im Ordner X").
  Use "." only when the user says "here", "this folder", "hier" or names no folder.
- destination: the target folder for move and copy, as the user names it. Otherwise "".
- extensions: file endings the user says, such as mp3 or pdf. Never add endings the user did not say. Otherwise [].
- kinds: audio, video, image, document or archive when the user says a kind (photos, Bilder, songs, Videos)
  and no ending. Otherwise [].
- name_contains: a word the file name must contain ("with X in the name", "mit X im Namen"). Otherwise "".
- older_than, age_unit: the age limit, number and unit as the user says them. Otherwise 0 and days.
- larger_than, size_unit: the size limit, number and unit as the user says them. Otherwise 0 and mb.
- group_by: for organize only: "month" when sorting by date or month, else "type". Otherwise "".

Examples:
Move all txt files from Notes to Old
${example("move", "Notes", "Old", "", "\"txt\"", "", 0, "days", 0, "mb", "")}
Lösche hier alle Videos, die älter als 3 Wochen sind
${example("delete", ".", "", "\"video\"", "", "", 3, "weeks", 0, "mb", "")}
Lösche alle Bilder im Ordner Scans
${example("delete", "Scans", "", "\"image\"", "", "", 0, "days", 0, "mb", "")}
Copy everything bigger than 4 GB from Podcasts to Stick
${example("copy", "Podcasts", "Stick", "", "", "", 0, "days", 4, "gb", "")}
Verschiebe alle Dateien mit Entwurf im Namen aus Projekte nach Fertig
${example("move", "Projekte", "Fertig", "", "", "Entwurf", 0, "days", 0, "mb", "")}
Räum den Ordner Hörbücher auf
${example("organize", "Hörbücher", "", "", "", "", 0, "days", 0, "mb", "type")}
Wie spät ist es?
${example("none", "", "", "", "", "", 0, "days", 0, "mb", "")}"""

    private fun example(
        action: String, source: String, destination: String, kinds: String, extensions: String,
        name: String, older: Int, ageUnit: String, larger: Int, sizeUnit: String, groupBy: String
    ) = "{\"action\":\"$action\",\"source\":\"$source\",\"destination\":\"$destination\"," +
        "\"kinds\":[$kinds],\"extensions\":[$extensions],\"name_contains\":\"$name\"," +
        "\"older_than\":$older,\"age_unit\":\"$ageUnit\",\"larger_than\":$larger," +
        "\"size_unit\":\"$sizeUnit\",\"group_by\":\"$groupBy\"}"
}
