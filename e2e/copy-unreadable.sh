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
if ! scroll_to "Interner Speicher|Internal storage" || ! ui tap "Interner Speicher|Internal storage" >/dev/null ||
    ! wait_on filelist.FileListActivity 6 || ! scroll_to Android; then
    fail "the internal storage root opens"; finish
fi
if ! ui longtap "Android" >/dev/null || ! ui wait selectionCopyButton 5; then
    fail "the unreadable Android folder is selected"; finish
fi
if ! scroll_to ordo-e2e-unreadable || ! ui tap ordo-e2e-unreadable >/dev/null || ! ui wait "2 ausgewählt|2 selected" 5; then
    fail "the readable test folder is selected too"; finish
fi
if ! ui tap selectionCopyButton >/dev/null || ! scroll_to ordo-e2e-unreadable-to ||
    ! ui tap ordo-e2e-unreadable-to >/dev/null || ! ui wait action_paste 5; then
    fail "the copy target opens"; finish
fi
if ! ui tap action_paste >/dev/null || ! ui wait "kann nicht gelesen werden|can’t be read" 10; then
    fail "an unreadable folder raises the error dialog"; finish
fi
pass "an unreadable folder raises the error dialog"
check_or_abort "the dialog offers retry, skip and cancel" ui has "Überspringen|Skip"
if ! ui tap button2 >/dev/null || ! eventually 10 a shell "test -e $TO/ordo-e2e-unreadable/src/a.txt"; then
    fail "the readable files are copied after skipping"; finish
fi
guard
pass "the readable files are copied after skipping"
check "Ordo has not crashed" test "$(crashes)" = 0
a shell "rm -rf $DIR $TO"
finish
