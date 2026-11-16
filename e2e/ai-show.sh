#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# AI "show" and the checks around it, without the model (debug builds only): DebugAiActivity hands the command
# sheet a model answer, so the rules, the result list, the hints and the confirmations run as with the model.
# Writes (run only with run.sh --write): creates ordo-e2e-ai/ with three small files, moves one to the trash and
# removes all of it at the end (folder and its trash entries). SHOTS=<dir> also saves screenshots.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/sdcard/ordo-e2e-ai
DEBUG=me.zhanghai.android.files.ai.DebugAiActivity
shot() { [ -n "${SHOTS:-}" ] && a exec-out screencap -p > "$SHOTS/$1.png"; return 0; }
# ask TEXT ANSWER: the sheet with this command and this model answer.
ask() {
    a shell am start -W -n "$PKG/$DEBUG" --es text "'$1'" --es answer "'$2'" >/dev/null; sleep 3; guard
}
exists() { a shell "[ -f '$DIR/$1' ]"; }
not() { ! "$@"; }
not_has() { ! ui has "$1"; }

wake
a logcat -b crash -c
a shell am force-stop "$PKG"
if ! a shell pm dump "$PKG" | grep -q "$DEBUG"; then echo "SKIP: not a debug build (no DebugAiActivity)"; exit 0; fi
a shell "rm -rf $DIR; mkdir -p $DIR && dd if=/dev/zero of=$DIR/big_ordoe2e.mp4 bs=1024 count=2048 2>/dev/null &&
    dd if=/dev/zero of=$DIR/small_ordoe2e.mp4 bs=1024 count=10 2>/dev/null && echo note > $DIR/keep_ordoe2e.txt"

# --- the rules on the device's regex engine (ICU) give what rules.py gives, for every recorded lab case
FILES=/sdcard/Android/data/$PKG/files
a shell mkdir -p "$FILES"; a push "$HERE/../app/src/test/resources/ai/rules-golden.json" "$FILES/" >/dev/null
a logcat -c; a shell am start -n "$PKG/$DEBUG" --es golden rules-golden.json >/dev/null
for _ in $(seq 30); do line=$(a logcat -d -s OrdoGolden:I | grep -m1 equal); [ -n "$line" ] && break; sleep 1; done
echo "   ${line:-no answer}"
total=$(python3 -c "import json,sys; print(len(json.load(open(sys.argv[1]))['rows']))" "$HERE/../app/src/test/resources/ai/rules-golden.json")
check "rules on the device equal rules.py ($total cases)" grep -q " $total/$total equal" <<< "$line"
a shell rm -f "$FILES/rules-golden.json"

# --- show: a list in the app's own style, nothing to confirm
ask "zeig mir die ordoe2e videos größer als 1 mb" \
    '{"action":"show","kinds":["video"],"larger_than":1,"size_unit":"mb","name_contains":"ordoe2e"}'
check "show opens the result list" wait_on filelist.FileListActivity 8
check "the title says what was understood" ui wait "Videos mit „ordoe2e“ über 1 MB|Videos with “ordoe2e” over 1 MB" 10
check "the big video is listed" ui has big_ordoe2e.mp4
check "the small one is not" not_has small_ordoe2e.mp4
shot ai-show-list
ui longtap big_ordoe2e.mp4 >/dev/null 2>&1; sleep 1.5
check "long press selects as usual" ui has selectionBar
back; back

# --- a question never becomes a delete: the rules turn it into show
ask "kann ich die ordoe2e videos löschen?" '{"action":"delete","source":"ordo-e2e-ai","kinds":["video"],"name_contains":"ordoe2e"}'
check "a question shows a list instead of a delete" wait_on filelist.FileListActivity 8
check "nothing was deleted" exists big_ordoe2e.mp4
back

# --- something Ordo cannot know: a hint, never a plan
ask "zeig mir dateien die ich seit einer woche nicht geöffnet habe" '{"action":"delete","source":"Download"}'
check "the hint says Ordo cannot know it" ui wait "Das kann Ordo noch nicht wissen|Ordo can’t know that yet" 6
check "the hint offers unchanged files instead" ui has "Unveränderte Dateien zeigen|Show unchanged files"
check "no confirm button" not_has confirmButton
shot ai-show-hint
ui tap messageLayout >/dev/null 2>&1; sleep 1
check "a tap on the hint goes back to the command field" ui has commandEdit
back

# --- storage questions and duplicates go to the existing analysis pages, without the model
ask "Was kann ich löschen, damit ich wieder Platz habe?" '{"action":"delete","source":"Download"}'
check "a storage question opens the analysis" wait_on analysis.AnalysisActivity 8
check "on its largest files" ui wait "Große Dateien|Large files" 120
shot ai-show-storage
back
check "back leads to the analysis overview" on analysis.AnalysisActivity
back
ask "welche dateien habe ich doppelt?" '{"action":"none"}'
check "duplicates open the analysis" wait_on analysis.AnalysisActivity 8
check "on its duplicates" ui wait "Duplikate|Duplicates" 120
back; back

# --- delete keeps preview + confirm, and a file replaced since the preview is left alone
ask "lösche die ordoe2e videos im ordner ordo-e2e-ai" \
    '{"action":"delete","source":"ordo-e2e-ai","kinds":["video"],"name_contains":"ordoe2e"}'
check "delete shows the preview with a confirm button" ui wait confirmButton 8
check "nothing is deleted before the confirm" exists small_ordoe2e.mp4
a shell "echo replaced >> $DIR/big_ordoe2e.mp4"; sleep 1
ui tap confirmButton >/dev/null 2>&1; sleep 2
check "the note names the changed file" ui wait "seit der Vorschau geändert|changed since the preview" 6
check "the replaced file is still there" exists big_ordoe2e.mp4
check "the unchanged one went to the trash" not exists small_ordoe2e.mp4
a shell "rm -f /sdcard/.OrdoTrash/files/*ordoe2e* /sdcard/.OrdoTrash/info/*ordoe2e*"
a shell "rm -rf $DIR"
finish
