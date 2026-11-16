/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

/**
 * The prompts and grammars the on-device model can run with. TODAY is the shipped one (utils/command-bench/bench.py,
 * keep both in sync). K is prompt "K" of docs/KI-ZEIGEN-PHASE0B.md, word for word from tools/memory-lab/phase0b.py
 * (SYSTEM_K, grammar(FIELDS_K)); CommandRulesGoldenTest compares it with the exported lab version.
 * Which one runs decides [CommandDesign.current].
 */
object CommandPlanner {
    // Qwen3.5 chat format with thinking switched off, as llama-server renders it.
    fun prefix(system: String): String = "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n"

    fun suffix(text: String): String =
        text.replace("<|", "< |") + "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"

    const val GRAMMAR_TODAY = """root ::= "{" "\"action\":" action ",\"source\":" string ",\"destination\":" string ",\"kinds\":" kinds ",\"extensions\":" strings ",\"name_contains\":" string ",\"older_than\":" int ",\"age_unit\":" ageunit ",\"larger_than\":" int ",\"size_unit\":" sizeunit ",\"group_by\":" groupby "}"
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

    val SYSTEM_TODAY = """You turn one command for a file manager into a JSON plan. The command is German or English.

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

    // Plan K: 15 fields, with "show", a word inside the file, newer_than, sort and limit.
    const val GRAMMAR_K = """root ::= "{\"action\":" action ",\"source\":" string ",\"destination\":" string ",\"kinds\":" kinds ",\"extensions\":" strings ",\"name_contains\":" string ",\"content_contains\":" string ",\"older_than\":" int ",\"newer_than\":" int ",\"age_unit\":" ageunit ",\"larger_than\":" int ",\"size_unit\":" sizeunit ",\"sort\":" sort ",\"limit\":" int ",\"group_by\":" groupby "}"
action ::= "\"show\"" | "\"move\"" | "\"copy\"" | "\"delete\"" | "\"organize\"" | "\"none\""
kinds ::= "[" ( kind ( "," kind ){0,4} )? "]"
kind ::= "\"audio\"" | "\"video\"" | "\"image\"" | "\"document\"" | "\"archive\""
strings ::= "[" ( string ( "," string ){0,7} )? "]"
ageunit ::= "\"days\"" | "\"weeks\"" | "\"months\"" | "\"years\""
sizeunit ::= "\"mb\"" | "\"gb\""
groupby ::= "\"\"" | "\"type\"" | "\"month\""
sort ::= "\"\"" | "\"size\"" | "\"newest\""
string ::= "\"" char{0,80} "\""
char ::= [^"\\\x7F\x00-\x1F] | "\\" ["\\/nt]
int ::= "0" | [1-9] [0-9]{0,5}"""

    const val SYSTEM_K = """You turn one command for a file manager into a JSON plan. The command is German or English.

Fields:
- action: show (find, list or open files; every question about the user's files; never changes anything),
  move, copy, delete (only when the user says to delete), organize (tidy up into subfolders) or none (anything
  else: greetings, rename, share, send).
- source: the folder as the user names it. Downloads: "Download", camera photos: "Kamera", screenshots:
  "Screenshots", WhatsApp pictures: "WhatsApp Images". For show "" when no folder is named (whole phone).
- destination: the target folder for move and copy, else "".
- kinds: image, video, audio, document or archive when the user says a kind (Fotos, Videos, Musik).
- extensions: file endings the user says (pdf, apk, mp3). Never invent one.
- name_contains: a word the file name must contain.
- content_contains: a word inside the file, when the user asks what is written in it or does not know the name.
- older_than / newer_than with age_unit: "älter als" / recent files ("gestern" = newer_than 1 days).
- larger_than, size_unit: the size limit the user says.
- sort: "size" for the biggest files, "newest" for the latest ones; limit: how many files, else 0.
- group_by: organize only: "month" or "type".

Examples:
Command: Move all txt files from Notes to Old
{"action":"move","source":"Notes","destination":"Old","kinds":[],"extensions":["txt"],"name_contains":"","content_contains":"","older_than":0,"newer_than":0,"age_unit":"days","larger_than":0,"size_unit":"mb","sort":"","limit":0,"group_by":""}
Command: Lösche hier alle Videos, die älter als 3 Wochen sind
{"action":"delete","source":".","destination":"","kinds":["video"],"extensions":[],"name_contains":"","content_contains":"","older_than":3,"newer_than":0,"age_unit":"weeks","larger_than":0,"size_unit":"mb","sort":"","limit":0,"group_by":""}
Command: Räum den Ordner Hörbücher auf
{"action":"organize","source":"Hörbücher","destination":"","kinds":[],"extensions":[],"name_contains":"","content_contains":"","older_than":0,"newer_than":0,"age_unit":"days","larger_than":0,"size_unit":"mb","sort":"","limit":0,"group_by":"type"}
Command: Zeig mir die drei größten Dateien in Movies
{"action":"show","source":"Movies","destination":"","kinds":[],"extensions":[],"name_contains":"","content_contains":"","older_than":0,"newer_than":0,"age_unit":"days","larger_than":0,"size_unit":"mb","sort":"size","limit":3,"group_by":""}
Command: Welche PDFs von letzter Woche erwähnen die Steuer?
{"action":"show","source":"","destination":"","kinds":[],"extensions":["pdf"],"name_contains":"","content_contains":"Steuer","older_than":0,"newer_than":1,"age_unit":"weeks","larger_than":0,"size_unit":"mb","sort":"","limit":0,"group_by":""}
Command: Wie spät ist es?
{"action":"none","source":"","destination":"","kinds":[],"extensions":[],"name_contains":"","content_contains":"","older_than":0,"newer_than":0,"age_unit":"days","larger_than":0,"size_unit":"mb","sort":"","limit":0,"group_by":""}"""

    private fun example(
        action: String, source: String, destination: String, kinds: String, extensions: String,
        name: String, older: Int, ageUnit: String, larger: Int, sizeUnit: String, groupBy: String
    ) = "{\"action\":\"$action\",\"source\":\"$source\",\"destination\":\"$destination\"," +
        "\"kinds\":[$kinds],\"extensions\":[$extensions],\"name_contains\":\"$name\"," +
        "\"older_than\":$older,\"age_unit\":\"$ageUnit\",\"larger_than\":$larger," +
        "\"size_unit\":\"$sizeUnit\",\"group_by\":\"$groupBy\"}"
}
