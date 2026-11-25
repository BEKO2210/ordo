#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# farm: orient=once timeout=1800
# A failed save never damages the file (blocker before 1.0.0), WRITES: creates /storage/emulated/0/ordo-e2e-save with a
# 128-KiB text file and, for a few seconds, fills the device's storage up to the last few KB with one file under
# /data/local/tmp (removed right after the save, on any exit too), then removes everything.
# The file is opened in Ordo's text editor (viewer.text.TextEditorActivity), one word is typed in, and Save
# (action_save) runs while the storage is full, so the write must fail. Afterwards (storage free again) the file on
# disk still has exactly its old size and MD5, and the editor still has the typed word (nothing the user typed is
# lost). Regression: the save opened the file with TRUNCATE_EXISTING and wrote into it (filejob/FileJobs.kt, write()):
# a failing write left it empty or cut off. Filling the storage is only done on an emulator or with E2E_FILL_DISK=1
# (a real phone with a full storage may close apps or lose data of its own); else SKIP, by hand.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/ordo-e2e-save
FILE=$DIR/ordo-e2e-note.txt
FILL=/data/local/tmp/ordo-e2e-fill
WORD=ordoE2Eatomic
if ! is_emulator && [ "${E2E_FILL_DISK:-}" != 1 ]; then
    echo "SKIP text editor atomic save (fills the storage: only on an emulator or with E2E_FILL_DISK=1)"
    exit 0
fi
tmp=$(mktemp -d)
unfill() { a shell "rm -f $FILL $FILL.2 $FILL.3"; }
trap 'unfill; a shell am force-stop "$PKG"; a shell "rm -rf $DIR"; rm -rf "$tmp"' EXIT

# Exactly 128 KiB, a whole number of 4-KB blocks: any word typed in needs one block more than the old file had, so
# even a save that first cuts the file to 0 (and frees its blocks) cannot finish on the full storage.
python3 -c '
import sys
text = "".join(f"Zeile {i:04d}: ordo-e2e original, must survive a failed save.\n" for i in range(1, 4000))
open(sys.argv[1], "w").write(text[:131071] + "\n")
' "$tmp/ordo-e2e-note.txt"
a shell "rm -rf $DIR; mkdir -p $DIR"
a push "$tmp/ordo-e2e-note.txt" "$DIR/" >/dev/null
state() { a shell "stat -c %s $FILE; md5sum $FILE" | tr -d '\r' | awk 'NR == 1 { s = $1 } NR == 2 { print s, $1 }'; }
before=$(state)
echo "INFO before: size and MD5 $before"
free_kb() { a shell df -k /storage/emulated/0 | tail -1 | awk '{ print $4 }' | tr -d '\r'; }

wake; grant; a logcat -b crash -c
a shell am force-stop "$PKG"
a shell am start -W -a android.intent.action.VIEW -d "file://$FILE" -t text/plain \
    -n "$PKG/me.zhanghai.android.files.viewer.text.TextEditorActivity" >/dev/null
check "the text editor opens" wait_on viewer.text.TextEditorActivity 8
guard
ui wait "textEdit" 10 >/dev/null
ui tap textEdit >/dev/null; sleep 1; guard
a shell input text "$WORD"; sleep 1
a shell input keyevent 111; sleep 1 # the keyboard away (Escape)
guard
check "the editor marks the text as changed (*name)" ui has "*ordo-e2e-note.txt"
# Where Save is, read now: with the storage full even a screen read (it writes to /data/local/tmp) fails.
read -r sx1 sy1 sx2 sy2 < <(ui bounds action_save | head -1)

# Fill: one file of nearly all free space, then the rest in 4-KB blocks until the storage says no.
free=$(free_kb)
echo "INFO free before filling: ${free:-?} KB"
a shell "fallocate -l $(( ${free:-0} > 256 ? free - 128 : 0 ))K $FILL 2>/dev/null || dd if=/dev/zero of=$FILL bs=1048576 count=$(( ${free:-0} / 1024 )) 2>/dev/null"
a shell "dd if=/dev/zero of=$FILL.2 bs=4096 2>/dev/null; dd if=/dev/zero of=$FILL.3 bs=512 2>/dev/null"
left=$(free_kb)
echo "INFO free after filling: ${left:-?} KB"
if [ "${left:-999999}" -gt 4 ]; then
    unfill
    echo "SKIP the storage could not be filled (${left:-?} KB left; /data/local/tmp may not share it with $DIR)"
    a shell am force-stop "$PKG"; finish
fi
guard
if [ -z "${sy2:-}" ]; then unfill; fail "the Save button was found"; a shell am force-stop "$PKG"; finish; fi
a shell input tap $(((sx1 + sx2) / 2)) $(((sy1 + sy2) / 2)); sleep 5
unfill; sleep 2
# A failed write shows Ordo's file job error dialog (Retry / Skip / Cancel): give up the save, never retry.
guard
for _ in 1 2 3; do
    ui has "Abbrechen|Cancel" || ui has "Überspringen|Skip" || break
    ui tap "Abbrechen|Cancel" >/dev/null 2>&1 || ui tap "Überspringen|Skip" >/dev/null 2>&1; sleep 2
done
after=$(state)
echo "INFO after: size and MD5 $after"
check "a failed save leaves the file exactly as it was (size and MD5)" test "$after" = "$before"
check "the file is not empty after the failed save" test "${after%% *}" -gt 0
guard
typed() { ui texts | grep -q "$WORD"; }
check "the editor still has the typed text" typed
a shell am force-stop "$PKG"
finish
