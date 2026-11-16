#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Read-only checks around the AI "show" list (PR #51 review):
# - Another app cannot open a "show" list: FileListActivity is exported, so an explicit intent with the list's
#   extras (and a made-up token) must open the normal file list, never Ordo's private folder. Holds in every build.
# - Debug builds: the rules on the device's regex engine (ICU) give what rules.py gives for every recorded lab case
#   (the unit test checks the JVM engine), and so does the net of the designs Guarded (rules2.py) and Tuned
#   (rules3.py) for their fixtures. Skipped on a release build, which has no DebugAiActivity.
. "$(dirname "$0")/lib.sh" "$@"
not() { ! "$@"; }
wake
a logcat -b crash -c
a shell am force-stop "$PKG"

# --- an outside intent never reaches the show list
LIST=me.zhanghai.android.files.filelist.FileListActivity
E=me.zhanghai.android.files.extra
a shell am start -W -n "$PKG/$LIST" --es $E.SHOW_TOKEN forged-token \
    --es $E.SHOW_PLAN "'{\"action\":\"show\"}'" --esa $E.SHOW_SOURCES "/data/data/$PKG/files,/data/data/$PKG/no_backup" \
    --es $E.SHOW_TITLE PRIVATTEST --es $E.SHOW_DETAIL PRIVATTEST >/dev/null
sleep 3; guard
check "a forged show intent opens the normal file list" on filelist.FileListActivity
check "it shows nothing of the forged request" not ui has PRIVATTEST
check "nor Ordo's private files" not ui has llm-state
back

# --- rules on ICU (debug builds only)
DEBUG=me.zhanghai.android.files.ai.DebugAiActivity
FILES=/sdcard/Android/data/$PKG/files
FIXTURE="$HERE/../app/src/test/resources/ai/rules-golden.json"
a shell mkdir -p "$FILES"; a push "$FIXTURE" "$FILES/" >/dev/null
a logcat -c
if a shell am start -n "$PKG/$DEBUG" --es golden rules-golden.json 2>&1 | grep -q "does not exist"; then
    echo "SKIP rules on ICU: not a debug build"
else
    for _ in $(seq 30); do line=$(a logcat -d -s OrdoGolden:I | grep -m1 equal); [ -n "$line" ] && break; sleep 1; done
    echo "   ${line:-no answer}"
    total=$(python3 -c "import json,sys; print(len(json.load(open(sys.argv[1]))['rows']))" "$FIXTURE")
    check "rules on the device equal rules.py ($total cases)" grep -q " $total/$total equal" <<< "$line"
fi
a shell rm -f "$FILES/rules-golden.json"

# --- the net designs on ICU (debug builds only): rules2.decide (Guarded) and rules3.decide (Tuned)
for NET in guarded tuned; do
    FIXTURE="$HERE/../app/src/test/resources/ai/$NET-golden.json.gz"
    a push "$FIXTURE" "$FILES/" >/dev/null
    a logcat -c
    if a shell am start -n "$PKG/$DEBUG" --es golden_$NET $NET-golden.json.gz 2>&1 | grep -q "does not exist"; then
        echo "SKIP $NET net on ICU: not a debug build"
    else
        # thousands of cases on an emulator CPU: give it time
        for _ in $(seq 900); do line=$(a logcat -d -s OrdoGolden:I | grep -m1 -E "$NET .*(equal|failed)"); [ -n "$line" ] && break; sleep 1; done
        echo "   ${line:-no answer}"
        total=$(python3 -c "import gzip,json,sys; print(len(json.load(gzip.open(sys.argv[1],'rt'))['rows']))" "$FIXTURE")
        check "$NET net on the device equals the lab ($total cases)" grep -q " $total/$total equal" <<< "$line"
    fi
    a shell rm -f "$FILES/$NET-golden.json.gz"
done
finish
