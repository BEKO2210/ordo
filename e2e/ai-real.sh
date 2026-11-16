#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# The real 4B model in the app, end to end, with the shipped design (CommandDesign.Today): wand, typed commands, plan
# previews, one confirmed move, the "same file as in the preview" check and the saved prompt state (size, times, same
# answer with and without it, a damaged state is refused). Emulator and debug build only (it reads the app's private
# state through run-as and the answers from the debug log); SKIP elsewhere. Not part of run.sh: one run takes
# minutes of CPU per answer. Timings are emulator CPU timings and say nothing about a phone.
# The model is not downloaded: MODEL=<the pinned GGUF on this host> is pushed to the app's model folder once and the
# app checks its hash as usual. Writes only its own files: ordoai_* in Download, OrdoKiTest/ and OrdoKiZiel/.
# usage: MODEL=~/models/gguf/ordo-bench/Qwen3.5-4B-Q4_0.gguf e2e/ai-real.sh <emulator serial>; SHOTS=<dir> saves screenshots.
. "$(dirname "$0")/lib.sh" "$@"
DEBUG=me.zhanghai.android.files.ai.DebugAiActivity
TARGET=/sdcard/Android/data/$PKG/files/models/qwen3.5-4b-q4_0.gguf
STATE=no_backup/llm-state
T=/sdcard/OrdoKiTest; Z=/sdcard/OrdoKiZiel; D=/sdcard/Download

is_emulator || { echo "SKIP: not an emulator (this test is for the emulator only)"; exit 0; }
if a shell am start -W -n "$PKG/$DEBUG" --ez show_design false 2>&1 | grep -q "does not exist"; then
    echo "SKIP: not a debug build (no DebugAiActivity)"; exit 0
fi
[ -f "${MODEL:-}" ] || { echo "SKIP: MODEL=<path to Qwen3.5-4B-Q4_0.gguf> not set"; exit 0; }

run_as() { a shell run-as "$PKG" "$@"; }
log() { a logcat -d -s CommandEngine:I | tr -d '\r'; }
# wait_log REGEX S: polls the app log until a line matches (the model takes minutes on an emulator CPU).
wait_log() { local end=$((SECONDS + $2)); until log | grep -qE "$1"; do [ $SECONDS -ge "$end" ] && return 1; sleep 2; done; }
ms() { log | grep -oE "$1 [0-9]+ ms" | tail -1 | grep -oE '[0-9]+ ms'; }
listing() { a shell "ls -l $T $Z $D 2>/dev/null | grep ordoai | sort"; }
shot() { [ -n "${SHOTS:-}" ] && a exec-out screencap -p > "$SHOTS/$1.png"; return 0; }

# A cold start: app process gone, wand opened, the model loads (and reads the prompt or restores its state).
cold() {
    a shell am force-stop "$PKG"; a logcat -c
    launch
    ui tap assistantButton >/dev/null
    ui wait commandEdit 900 >/dev/null || return 1   # the first open hashes 2.6 GB
    wait_log "prompt (read|state restored)" 1800
}
# ask TEXT: types the command (ASCII: adb cannot type umlauts) and waits for the model's answer in the log.
ask() {
    a logcat -c
    ui tap commandEdit >/dev/null
    # Select all, then type over it: the field wraps, so END and DEL alone can leave words of the last command.
    a shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_A; a shell input keyevent KEYCODE_DEL
    a shell input text "'${1// /%s}'"
    ui texts | grep -qF "$1|commandEdit|" || { echo "  typed text differs from: $1"; return 1; }
    a shell input keyevent KEYCODE_ENTER
    wait_log "answer in" 900 || return 1
    sleep 3
    ANSWER=$(log | grep -oE 'answer in .*' | tail -1); echo "  $1 -> $ANSWER"
}
answer_json() { echo "${ANSWER#*: }"; }
back_to_input() { ui has editButton && ui tap editButton >/dev/null; sleep 1; }
no_confirm() { ! ui wait confirmButton 8; }
not() { ! "$@"; }
on_screen() { a shell uiautomator dump /sdcard/ordoai-ui.xml >/dev/null 2>&1; a shell cat /sdcard/ordoai-ui.xml | grep -qE "$1"; }
logged() { log | grep -qE "$1"; }
not_logged() { ! log | grep -qE "$1"; }

wake; grant
a shell cmd locale set-app-locales "$PKG" --locales de-DE >/dev/null 2>&1
# Back to the system language at the end, also after an abort.
trap 'a shell cmd locale set-app-locales "$PKG" --locales "" >/dev/null 2>&1' EXIT
a shell "rm -rf $T $Z; rm -f $D/ordoai_*; mkdir -p $T $Z $D
    echo a > $T/ordoai_rechnung.pdf; echo b > $T/ordoai_vertrag.pdf; echo c > $T/ordoai_notiz.txt
    echo d > $D/ordoai_handbuch.pdf; echo e > $D/ordoai_spiel.apk; echo f > $D/ordoai_foto.jpg"
if [ "$(a shell stat -c %s "$TARGET" 2>/dev/null | tr -d '\r')" != "$(stat -c %s "$MODEL")" ]; then
    a shell mkdir -p "${TARGET%/*}"; a push "$MODEL" "$TARGET" >/dev/null || { fail "model pushed"; finish; }
fi
run_as rm -rf "$STATE"

# --- 1st cold start: hash check, load, prompt read, state written
started=$SECONDS
check "the wand opens the command sheet with the real model" cold
echo "  first open incl. hash check: $((SECONDS - started)) s; load $(ms 'threads in'), prompt read $(ms 'prompt read in')"
check "the prompt state is written" wait_log "prompt state saved" 60
log | grep -E "loaded|saved|restored|read in" | sed 's/^/  /'

# --- previews for five commands; nothing is executed without the confirm
before=$(listing)
ask "Verschiebe alle PDFs aus Download nach Documents"
check "move PDFs: preview with confirm" ui wait confirmButton 20
check "move PDFs: the PDF is listed" on_screen ordoai_handbuch.pdf
shot ai-real-move; first=$(answer_json); back_to_input
ask "Entferne alle APKs in Download"
check "delete APKs: preview with the confirm button" ui wait confirmButton 20
check "delete APKs: the APK is listed" on_screen ordoai_spiel.apk
check "delete APKs: the trash note" ui has noteText
shot ai-real-delete; back_to_input
ask "Sortiere den Ordner Download nach Typ"
check "tidy Download: preview with confirm" ui wait confirmButton 20
back_to_input
ask "Wie viele Fotos habe ich auf dem Handy?"
check "a question gets no confirm button" no_confirm
back_to_input
ask "Loesche alles im Download Ordner"
check "delete all in Download: preview with the confirm button" ui wait confirmButton 20
back_to_input
check "nothing changed on disk without a confirm" test "$(listing)" = "$before"

# --- one confirmed move on the script's own files
ask "Verschiebe alle PDFs aus OrdoKiTest nach OrdoKiZiel"
ui wait confirmButton 20 >/dev/null
# The preview lists the names as lines of one text, so look for them inside it (ui.py matches whole texts).
check "move OrdoKiTest: preview lists both PDFs" on_screen "ordoai_rechnung.pdf.*ordoai_vertrag.pdf|ordoai_vertrag.pdf.*ordoai_rechnung.pdf"
ui tap confirmButton >/dev/null; sleep 4
check "the PDFs are in OrdoKiZiel" a shell "[ -f $Z/ordoai_rechnung.pdf ] && [ -f $Z/ordoai_vertrag.pdf ]"
check "the txt stayed" a shell "[ -f $T/ordoai_notiz.txt ] && [ ! -f $T/ordoai_rechnung.pdf ]"

# --- a file changed between preview and confirm is skipped and named
a shell "mv $Z/ordoai_*.pdf $T/"
ui tap assistantButton >/dev/null; ui wait commandEdit 30 >/dev/null
ask "Verschiebe alle PDFs aus OrdoKiTest nach OrdoKiZiel"
ui wait confirmButton 20 >/dev/null
a shell "echo changed >> $T/ordoai_vertrag.pdf"; sleep 1
ui tap confirmButton >/dev/null; sleep 2
check "the note names the changed file" on_screen "seit der Vorschau geändert|changed since the preview"
check "the changed file stayed" a shell "[ -f $T/ordoai_vertrag.pdf ] && [ ! -f $Z/ordoai_vertrag.pdf ]"
check "the unchanged one moved" a shell "[ -f $Z/ordoai_rechnung.pdf ]"

# --- 2nd cold start restores the state; same answer as without it
STATEFILE=$(run_as ls "$STATE" | tr -d '\r' | grep '\.state$' | head -1)
echo "  state file: $(run_as stat -c %s "$STATE/$STATEFILE" | tr -d '\r') bytes"
cold
check "cold start 2 restores the saved state" logged "prompt state restored"
echo "  restore: $(log | grep -oE 'restored: .*' | tail -1); load $(ms 'threads in')"
ask "Verschiebe alle PDFs aus Download nach Documents"; with=$(answer_json)
run_as rm -f "$STATE/$STATEFILE"
cold
echo "  without state: prompt read $(ms 'prompt read in'); load $(ms 'threads in')"
check "without the state the prompt is read again" logged "prompt read in"
ask "Verschiebe alle PDFs aus Download nach Documents"; without=$(answer_json)
check "same answer with and without the state" test -n "$with" -a "$with" = "$without"
check "same answer as the first run" test "$with" = "$first"

# --- a damaged state (one byte flipped) is refused, the prompt is read, the answer stays the same
# The answer above came after the state was written (one thread loads, saves and answers).
sum0=$(run_as sha256sum "$STATE/$STATEFILE")
a shell "printf '\\377' | run-as $PKG dd of=$STATE/$STATEFILE bs=1 seek=4096 count=1 conv=notrunc 2>/dev/null"
check "one byte of the state was changed" test "$(run_as sha256sum "$STATE/$STATEFILE")" != "$sum0"
cold
check "a damaged state is not restored" not_logged "prompt state restored"
check "the prompt is read instead" logged "prompt read in"
check "and the state is not written again" test -z "$(run_as ls "$STATE" | grep '\.state$')"
ask "Verschiebe alle PDFs aus Download nach Documents"
check "same answer after the damaged state" test "$(answer_json)" = "$with"

# --- ADAPTER=<a LoRA GGUF for this model> (optional): a debug design with the adapter on top of Today
if [ -f "${ADAPTER:-}" ]; then
    LORA=ordoai-test-adapter.gguf; SPEC="$LORA,$(stat -c %s "$ADAPTER"),$(sha256sum "$ADAPTER" | cut -c1-64)"
    lora() { a shell am start -W -n "$PKG/$DEBUG" --es adapter "'$1'" >/dev/null; }
    a push "$ADAPTER" "${TARGET%/*}/$LORA" >/dev/null
    lora "$SPEC"
    cold
    check "the adapter loads (its hash in the log)" logged "adapter ${SPEC##*,}"
    check "the adapter gets its own prompt state, not the base one" not_logged "prompt state restored"
    ask "Verschiebe alle PDFs aus Download nach Documents"
    check "an answer with the adapter" test -n "$(answer_json)"
    [ "$(answer_json)" = "$with" ] && echo "  (same answer as the base model for this command)" \
        || echo "  (differs from the base model's answer)"
    cold
    check "the adapter's own state is restored on the next start" logged "prompt state restored"
    # A wrong hash: the file is refused like a damaged download, removed, and the sheet says so.
    a push "$ADAPTER" "${TARGET%/*}/$LORA" >/dev/null
    lora "$LORA,$(stat -c %s "$ADAPTER"),$(printf '0%.0s' $(seq 64))"
    a shell am force-stop "$PKG"; a logcat -c; launch; ui tap assistantButton >/dev/null
    check "a wrong-hash adapter is refused with a message" ui wait "Der Download war beschädigt und wurde entfernt. Bitte versuch es noch einmal.|The download was damaged and has been removed. Please try again." 300
    check "and removed" not a shell "[ -f '${TARGET%/*}/$LORA' ]"
    check "the model stays" a shell "[ -f '$TARGET' ]"
    lora ""
    cold
    check "without the adapter the base design runs again" not_logged "adapter "
    ask "Verschiebe alle PDFs aus Download nach Documents"
    check "with the base answer as before" test "$(answer_json)" = "$with"
fi

a shell am force-stop "$PKG"
run_as rm -rf "$STATE"
a shell "rm -rf $T $Z /sdcard/ordoai-ui.xml; rm -f $D/ordoai_* '${TARGET%/*}/ordoai-test-adapter.gguf'"
finish
