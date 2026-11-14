/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

/** Prompt and grammar for the on-device model. Prompt matches utils/command-bench/bench.py, keep both in sync. */
object CommandPlanner {
    // Qwen3.5 chat format with thinking switched off, as llama-server renders it.
    val PREFIX: String
        get() = "<|im_start|>system\n$SYSTEM<|im_end|>\n<|im_start|>user\n"

    fun suffix(text: String): String =
        text.replace("<|", "< |") + "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"

    // The JSON plan of utils/command-bench/bench.py (SCHEMA) as a llama.cpp grammar, so the model can only answer with a valid plan.
    const val GRAMMAR = """root ::= "{" "\"action\":" action ",\"source\":" string ",\"destination\":" string ",\"kinds\":" kinds ",\"extensions\":" strings ",\"name_contains\":" string ",\"older_than\":" int ",\"age_unit\":" ageunit ",\"larger_than\":" int ",\"size_unit\":" sizeunit ",\"group_by\":" groupby "}"
action ::= "\"move\"" | "\"copy\"" | "\"delete\"" | "\"organize\"" | "\"none\""
kind ::= "\"audio\"" | "\"video\"" | "\"image\"" | "\"document\"" | "\"archive\""
kinds ::= "[" ( kind ( "," kind ){0,4} )? "]"
strings ::= "[" ( string ( "," string ){0,7} )? "]"
ageunit ::= "\"days\"" | "\"weeks\"" | "\"months\"" | "\"years\""
sizeunit ::= "\"mb\"" | "\"gb\""
groupby ::= "\"\"" | "\"type\"" | "\"month\""
string ::= "\"" char{0,80} "\""
char ::= [^"\\\x7F\x00-\x1F] | "\\" ["\\/nt]
int ::= "0" | [1-9] [0-9]{0,5}"""

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
