#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# The real 4B model in the app with the net design Guarded (prompt C + rules2), end to end: show by kind, time and
# size, a content question, an exclusion, a move with a name filter, a delete that needs the confirm, an ambiguous
# command that asks back, something Ordo cannot do, a question, two in English; then the five commands of
# ai-real.sh as regression and the saved prompt state of the new prompt. Checks every list and preview against
# files this script creates, and that nothing changes without a confirm. Emulator + debug build only; times are
# emulator CPU times and say nothing about a phone.
# DESIGN=guarded (default) picks the design through DebugAiActivity; DESIGN=settings leaves that empty and turns on
# the preview switch in Settings › AI assistant, the way a user does (and turns it off again at the end). DESIGN=tuned with ADAPTER=<gguf>
# runs the same commands with the tuned model (adapter pushed under the name the design expects).
# usage: MODEL=<Qwen3.5-4B-Q4_0.gguf> [DESIGN=guarded|default|tuned] [SHOTS=<dir>] e2e/ai-guarded.sh <emulator serial>
. "$(dirname "$0")/lib.sh" "$@"
DEBUG=me.zhanghai.android.files.ai.DebugAiActivity
MODELS=/sdcard/Android/data/$PKG/files/models
TARGET=$MODELS/qwen3.5-4b-q4_0.gguf
STATE=no_backup/llm-state
T=/sdcard/OrdoKiTest; Z=/sdcard/OrdoKiZiel; D=/sdcard/Download
DESIGN=${DESIGN:-guarded}

is_emulator || { echo "SKIP: not an emulator (this test is for the emulator only)"; exit 0; }
if a shell am start -W -n "$PKG/$DEBUG" --ez show_design false 2>&1 | grep -q "does not exist"; then
    echo "SKIP: not a debug build (no DebugAiActivity)"; exit 0
fi
[ -f "${MODEL:-}" ] || { echo "SKIP: MODEL=<path to Qwen3.5-4B-Q4_0.gguf> not set"; exit 0; }

run_as() { a shell run-as "$PKG" "$@"; }
log() { a logcat -d -s CommandEngine:I CommandSheet:I | tr -d '\r'; }
wait_log() { local end=$((SECONDS + $2)); until log | grep -qE "$1"; do [ $SECONDS -ge "$end" ] && return 1; sleep 2; done; }
ms() { log | grep -oE "$1 [0-9]+ ms" | tail -1 | grep -oE '[0-9]+ ms'; }
listing() { a shell "ls -l $T $Z $D 2>/dev/null | grep ordoai | sort"; }
shot() { [ -n "${SHOTS:-}" ] && a exec-out screencap -p > "$SHOTS/$1.png"; return 0; }
dump() { a shell uiautomator dump /sdcard/ordoai-ui.xml >/dev/null 2>&1; a shell cat /sdcard/ordoai-ui.xml; }
on_screen() { dump | grep -qE "$1"; }
not_on_screen() { ! dump | grep -qE "$1"; }
not() { ! "$@"; }
logged() { log | grep -qE "$1"; }
not_logged() { ! log | grep -qE "$1"; }
TIMES=()
exec 3>&1

cold() {
    a shell am force-stop "$PKG"; a logcat -c
    launch
    ui tap assistantButton >/dev/null
    ui wait commandEdit 900 >/dev/null || return 1
    wait_log "prompt (read|state restored)" 1800
}
# A fresh sheet for every command, whatever the last one left open (a list, a preview, a hint): the app is restarted
# and the model loads from its saved prompt state (seconds), so no step depends on the screen before it.
reopen() {
    a shell am force-stop "$PKG"; a logcat -c
    launch
    ui tap assistantButton >/dev/null
    ui wait commandEdit 120 >/dev/null
    wait_log "prompt (read|state restored)" 600
}
# ask TEXT [model]: types the command (ASCII: adb cannot type umlauts). With "model" it waits for the model's answer;
# without, it only waits for the sheet to react (a refusal before the model).
ask() {
    reopen
    a logcat -c
    ui tap commandEdit >/dev/null
    a shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_A; a shell input keyevent KEYCODE_DEL
    a shell input text "'${1// /%s}'"
    # the emulator keyboard may turn "ueber" into "über": compare with umlauts spelled out
    ui texts | sed 's/ä/ae/g; s/ö/oe/g; s/ü/ue/g; s/Ä/Ae/g; s/Ö/Oe/g; s/Ü/Ue/g; s/ß/ss/g' | grep -qF "$1|commandEdit|" ||
        { echo "  the keyboard changed the command (autocorrect); it runs as typed on screen:"
          ui texts | grep commandEdit | sed 's/^/    field: /'; }
    a shell input keyevent KEYCODE_ENTER
    ANSWER=""
    if [ "${2:-model}" = model ]; then
        wait_log "answer in" 900 || return 1
        ANSWER=$(log | grep -oE 'answer in .*' | tail -1)
        TIMES+=("$(grep -oE '[0-9]+ ms' <<< "$ANSWER")")
    fi
    sleep 4
    echo "  $1 -> ${ANSWER:-(no model call)}"
}
answer_json() { echo "${ANSWER#*: }"; }
in_list() {
    wait_on filelist.FileListActivity 30 && ui wait "$1" 30 && return 0
    # fd 3 is the script's output: check() silences its command's own output
    echo "    on $(top); screen: $(ui texts | cut -d'|' -f1 | grep -v '^$' | head -12 | tr '\n' ';')" >&3
    log | grep -E "net |W CommandSheet" | tail -3 | sed 's/^/    /' >&3
    return 1
}

wake; grant
# The emulator keyboard autocorrects typed commands ("Loesche" -> "Löschen"): type through the voice input method,
# which leaves key events alone, and give the keyboard back at the end.
KEYBOARD=$(a shell settings get secure default_input_method | tr -d '\r')
a shell ime set com.google.android.tts/com.google.android.apps.speech.tts.googletts.settings.asr.voiceime.VoiceInputMethodService >/dev/null 2>&1
[ -n "$KEYBOARD" ] && a shell ime disable "$KEYBOARD" >/dev/null 2>&1   # else an input field brings it back
a shell cmd locale set-app-locales "$PKG" --locales de-DE >/dev/null 2>&1
trap 'a shell cmd locale set-app-locales "$PKG" --locales "" >/dev/null 2>&1; a shell am start -n "$PKG/$DEBUG" --es design "" >/dev/null 2>&1; [ -n "$KEYBOARD" ] && { a shell ime enable "$KEYBOARD"; a shell ime set "$KEYBOARD"; } >/dev/null 2>&1' EXIT
PREVIEW="Neue Befehle ausprobieren (Vorschau)|Try new commands (preview)"
preview_switch() {   # opens Settings › AI assistant and taps the preview switch
    a shell am start -W -n "$PKG/me.zhanghai.android.files.settings.SettingsActivity" >/dev/null; sleep 2
    ui tap "KI-Assistent|AI assistant" >/dev/null; sleep 1
    ui tap "$PREVIEW" >/dev/null; sleep 1
}
case $DESIGN in
    settings)
        a shell am start -W -n "$PKG/$DEBUG" --es design "''" >/dev/null
        launch; preview_switch
        check "the preview switch is on" ui checked "$PREVIEW"
        a shell am force-stop "$PKG" ;;
    *) a shell am start -W -n "$PKG/$DEBUG" --es design "$DESIGN" >/dev/null ;;
esac
a shell am start -W -n "$PKG/$DEBUG" --es adapter "''" >/dev/null
if [ "$DESIGN" = tuned ]; then
    [ -f "${ADAPTER:-}" ] || { echo "SKIP: DESIGN=tuned needs ADAPTER=<the frozen adapter>"; exit 0; }
    a shell mkdir -p "$MODELS"; a push "$ADAPTER" "$MODELS/ordo-commands-1.gguf" >/dev/null
fi
# The script's own files: names, kinds, times and sizes the checks below know.
a shell "rm -rf $T $Z; rm -f $D/ordoai_*; mkdir -p $T $Z $D
    echo a > $T/ordoai_rechnung_telekom.pdf; echo b > $T/ordoai_vertrag.pdf; echo 'Steuer 2025 Belege' > $T/ordoai_notiz.txt
    echo c > $T/ordoai_alt.txt; touch -d '2024-01-10 10:00' $T/ordoai_alt.txt
    echo d > $D/ordoai_handbuch.pdf; echo e > $D/ordoai_spiel.apk; echo f > $D/ordoai_foto.jpg
    echo h > $D/ordoai_app2.apk; echo i > $D/ordoai_text.txt
    dd if=/dev/zero of=$D/ordoai_film.mp4 bs=1000000 count=120 2>/dev/null; echo g > $D/ordoai_klein.mp4"
if [ "$(a shell stat -c %s "$TARGET" 2>/dev/null | tr -d '\r')" != "$(stat -c %s "$MODEL")" ]; then
    a shell mkdir -p "$MODELS"; a push "$MODEL" "$TARGET" >/dev/null || { fail "model pushed"; finish; }
fi
run_as rm -rf "$STATE"

started=$SECONDS
check "the wand opens the command sheet with the real model ($DESIGN)" cold
echo "  first open incl. hash check: $((SECONDS - started)) s; load $(ms 'threads in'), prompt read $(ms 'prompt read in')"
[ "$DESIGN" = tuned ] && check "the adapter is loaded" logged "adapter 30ac6052"
check "the prompt state is written" wait_log "prompt state saved" 60
before=$(listing)

# --- 1 show by kind
ask "Zeig mir alle PDFs in OrdoKiTest"
check "show by kind: result list with both PDFs" in_list ordoai_rechnung_telekom.pdf
check "show by kind: the other PDF too" ui has ordoai_vertrag.pdf
check "show by kind: no txt" not ui has ordoai_notiz.txt
shot guarded-list-de
# --- 2 show by time
ask "Welche Dateien in OrdoKiTest sind aelter als ein Jahr?"
check "show by time: the old file" in_list ordoai_alt.txt
check "show by time: not the new ones" not ui has ordoai_vertrag.pdf
# --- 3 show by size
ask "Zeig mir die Videos ueber 100 MB"
check "show by size: the big video" in_list ordoai_film.mp4
check "show by size: not the small one" not ui has ordoai_klein.mp4
# --- 4 a content question
ask "In welcher Datei in OrdoKiTest steht Steuer?"
check "content question: the note with the word" in_list ordoai_notiz.txt
# --- 5 an exclusion
ask "Zeig mir alle Dateien in OrdoKiTest ausser PDFs"
check "exclusion: the txt files" in_list ordoai_notiz.txt
check "exclusion: no PDF" not ui has ordoai_vertrag.pdf
# --- 6 a move with a name filter: the preview lists only the matching file
ask "Verschiebe die Telekom PDFs aus OrdoKiTest nach OrdoKiZiel"
check "move with a name: preview with confirm" ui wait confirmButton 20
check "move with a name: the Telekom PDF is listed" on_screen ordoai_rechnung_telekom.pdf
check "move with a name: the other PDF is not" not_on_screen ordoai_vertrag.pdf
# --- 7 a delete needs the confirm
ask "Loesche alle APKs in Download"
check "delete APKs: preview with the confirm button" ui wait confirmButton 20
check "delete APKs: the APK is listed" on_screen ordoai_spiel.apk
# exactly the two APKs of the script's seven files in Download, nothing else
check "delete APKs: the second APK too" on_screen ordoai_app2.apk
check "delete APKs: exactly 2 files" on_screen "text=\"2 (Dateien|files)"
check "delete APKs: no other file" not_on_screen "ordoai_handbuch.pdf|ordoai_foto.jpg|ordoai_text.txt|ordoai_film.mp4"
# --- 8 ambiguous: asks back and keeps the typed command
ask "Verschieb die alten Sachen"
check "ambiguous: a question back, no confirm" not ui wait confirmButton 5
check "ambiguous: the question is shown" on_screen "Kurz nachgefragt|Quick question"
# the keyboard may have written "Verschiebe": the command stays in the field as typed on screen
check "ambiguous: the typed command stays" on_screen "die alten Sachen"
shot guarded-ask-de
# --- 9 something Ordo cannot do: answered before the model
ask "Pack alle Fotos aus Download in eine ZIP Datei" none
check "cannot do: the hint, without the model" on_screen "ZIP"
check "cannot do: no model call" not_logged "answer in"
check "cannot do: no confirm" not ui has confirmButton
shot guarded-refusal-de
# --- 10 a question
ask "Wie spaet ist es?"
check "a question: no confirm" not ui wait confirmButton 5
# --- 11/12 English
ask "Show me the photos in Download"
check "English show: the photo" in_list ordoai_foto.jpg
ask "Delete the apk files in Download"
check "English delete: preview with the confirm button" ui wait confirmButton 20
check "English delete: exactly the 2 APKs" on_screen "text=\"2 (Dateien|files)"
check "English delete: no other file" not_on_screen "ordoai_handbuch.pdf|ordoai_foto.jpg|ordoai_text.txt"
check "nothing changed on disk without a confirm" test "$(listing)" = "$before"

# --- regression: the five commands of ai-real.sh
ask "Verschiebe alle PDFs aus Download nach Documents"; first=$(answer_json)
check "regression move PDFs: preview with confirm" ui wait confirmButton 20
check "regression move PDFs: the PDF is listed" on_screen ordoai_handbuch.pdf
ask "Entferne alle APKs in Download"
check "regression delete APKs: preview with the confirm button" ui wait confirmButton 20
check "regression delete APKs: the trash note" ui has noteText
ask "Sortiere den Ordner Download nach Typ"
check "regression tidy Download: preview with confirm" ui wait confirmButton 20
ask "Wie viele Fotos habe ich auf dem Handy?"
check "regression question: no confirm button" not ui wait confirmButton 5
ask "Loesche alles im Download Ordner"
check "regression delete all in Download: preview with the confirm button" ui wait confirmButton 20
ALL=$(a shell "ls $D | wc -l" | tr -d '\r')
check "regression delete all: all $ALL files of Download" on_screen "text=\"$ALL (Dateien|files)"
ui tap confirmButton >/dev/null; sleep 2
check "regression delete all: a second confirmation, as before" ui has "Abbrechen|Cancel"
ui tap "Abbrechen|Cancel" >/dev/null; sleep 1
check "regression: nothing changed without a confirm" test "$(listing)" = "$before"

# --- words that find nothing together: the preview says which were left out, as typed
ask "Verschiebe die Telekom Vodafone Rechnung aus OrdoKiTest nach OrdoKiZiel"
check "dropped words: preview with confirm" ui wait confirmButton 20
check "dropped words: the note says what was left out" on_screen "zusammen habe ich nichts gefunden|together, so I only searched"
shot guarded-dropped-de

# --- one decision over the whole storage with three content words: the counting time (emulator CPU). A phone has
# thousands of files: 18,000 of the script's own (180 folders, every tenth a text file), removed at the end.
a shell 'M=/sdcard/OrdoKiMasse; mkdir -p $M; for d in $(seq 1 180); do mkdir -p $M/o$d; cd $M/o$d;
    for f in $(seq 1 100); do if [ $((f % 10)) = 0 ]; then echo "Notiz $d $f Einkauf Urlaub" > n$f.txt; else : > b$f.jpg; fi; done; done'
ask "Zeig mir alles mit Steuer Versicherung Telekom"
wait_log "net decision in" 600
COUNTING=$(log | grep -oE 'net decision in [0-9]+ ms, [0-9]+ folders listed' | tail -1)
echo "  whole storage, 3 words: ${COUNTING:-not logged}; files on the device: $(a shell 'find /sdcard/ -type f 2>/dev/null | wc -l' | tr -d '\r')"
check "whole storage: the counting time is logged" test -n "$COUNTING"
# closing the sheet while the net counts stops the walk
reopen; a logcat -c
ui tap commandEdit >/dev/null
a shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_A; a shell input keyevent KEYCODE_DEL
a shell input text "'Zeig%smir%salles%smit%sKaufvertrag%sKfz%sGarantie'"
a shell input keyevent KEYCODE_ENTER
end=$((SECONDS + 900)); until log | grep -q "answer in"; do [ $SECONDS -ge $end ] && break; sleep 0.3; done
a shell input keyevent BACK; a shell input keyevent BACK
sleep 5
log | grep -E "net (decision|counting)" | sed 's/^/  /'
check "closing the sheet during the count stops it (or it had already finished)" logged "net counting stopped|net decision in"
logged "net counting stopped" && echo "  stopped mid-count" || echo "  the count finished before the sheet closed"

# --- one confirmed move on the script's own files
ask "Verschiebe die Telekom PDFs aus OrdoKiTest nach OrdoKiZiel"
ui wait confirmButton 20 >/dev/null; ui tap confirmButton >/dev/null; sleep 4
check "confirmed: the Telekom PDF moved" a shell "[ -f $Z/ordoai_rechnung_telekom.pdf ] && [ ! -f $T/ordoai_rechnung_telekom.pdf ]"
check "confirmed: the other PDF stayed" a shell "[ -f $T/ordoai_vertrag.pdf ] && [ ! -f $Z/ordoai_vertrag.pdf ]"

# --- dark theme screenshots of a list, a question and a hint
if [ -n "${SHOTS:-}" ]; then
    a shell "mv $Z/ordoai_rechnung_telekom.pdf $T/ 2>/dev/null"
    a shell cmd uimode night yes >/dev/null; sleep 2
    ask "Zeig mir alle PDFs in OrdoKiTest"; in_list ordoai_vertrag.pdf >/dev/null; shot guarded-list-de-dark
    ask "Verschieb die alten Sachen"; shot guarded-ask-de-dark
    ask "Pack alle Fotos aus Download in eine ZIP Datei" none; shot guarded-refusal-de-dark
    ask "Verschiebe die Telekom Vodafone Rechnung aus OrdoKiTest nach OrdoKiZiel"; ui wait confirmButton 20 >/dev/null
    shot guarded-dropped-de-dark
    a shell cmd uimode night no >/dev/null; sleep 2
fi

# --- the new prompt's saved state: restored on the next cold start, same answer without it
STATEFILE=$(run_as ls "$STATE" | tr -d '\r' | grep '\.state$' | head -1)
echo "  state file: $(run_as stat -c %s "$STATE/$STATEFILE" | tr -d '\r') bytes"
cold
check "cold start 2 restores the saved state" logged "prompt state restored"
echo "  restore: $(log | grep -oE 'restored: .*' | tail -1); load $(ms 'threads in')"
ask "Verschiebe alle PDFs aus Download nach Documents"; with=$(answer_json)
run_as rm -f "$STATE/$STATEFILE"
cold
echo "  without state: prompt read $(ms 'prompt read in')"
ask "Verschiebe alle PDFs aus Download nach Documents"; without=$(answer_json)
check "same answer with and without the state" test -n "$with" -a "$with" = "$without"
check "same answer as earlier in the run" test "$with" = "$first"
echo "  answer times (emulator CPU): ${TIMES[*]}"

[ "$DESIGN" = settings ] && { a shell am force-stop "$PKG"; launch; preview_switch; check "the preview switch is off again" not ui checked "$PREVIEW"; }
a shell am force-stop "$PKG"
run_as rm -rf "$STATE"
a shell "rm -rf $T $Z /sdcard/OrdoKiMasse /sdcard/ordoai-ui.xml; rm -f $D/ordoai_* $MODELS/ordo-commands-1.gguf"
finish
