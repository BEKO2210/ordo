#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Copying a selection with a folder Ordo cannot read (Android/data on Android 11+, GB-9): the job asks
# retry / skip / cancel instead of ending with an error. WRITES: creates /sdcard/ordo-e2e-unreadable(-to) and removes both.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/sdcard/ordo-e2e-unreadable
TO=/sdcard/ordo-e2e-unreadable-to
sdk=$(a shell getprop ro.build.version.sdk | tr -d '\r')
[ "${sdk:-0}" -ge 30 ] || { echo "SKIP: needs Android 11+"; exit 0; }
a shell "rm -rf $DIR $TO; mkdir -p $DIR/src $TO && echo eins > $DIR/src/a.txt"
launch
# Home → internal storage root, where Android and the test folder stand side by side.
ui tap "Interner Speicher|Internal storage" >/dev/null; sleep 3; guard
ui longtap "Android" >/dev/null; sleep 1
ui tap ordo-e2e-unreadable >/dev/null; sleep 1
ui tap selectionCopyButton >/dev/null; sleep 1
ui tap ordo-e2e-unreadable-to >/dev/null; sleep 2
ui tap action_paste >/dev/null; sleep 6; guard
check "an unreadable folder raises the error dialog" ui has "kann nicht gelesen werden|can’t be read" 
check "the dialog offers retry, skip and cancel" ui has "Überspringen|Skip"
ui tap button2 >/dev/null; sleep 5; guard
check "the readable files are copied after skipping" a shell "test -e $TO/ordo-e2e-unreadable/src/a.txt"
check "Ordo has not crashed" test "$(crashes)" = 0
a shell "rm -rf $DIR $TO"
finish
