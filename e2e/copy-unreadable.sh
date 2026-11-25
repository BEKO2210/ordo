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
# list_to NAME: scrolls the file list until NAME stands above the selection bar. The list runs on behind the bar; on a
# landscape phone the swipe of scroll_to starts on the bar and moves nothing, and a row behind it is no row to tap
# (A15 landscape, 25.11.2026).
list_to() {
    local _ x1 y1 x2 y2 bot y
    for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
        bot=$(ui bounds selectionBar | awk '{ print $2; exit }')
        y=$(ui bounds "$1" nameText | awk '{ print int(($2 + $4) / 2); exit }')
        [ -n "$y" ] && [ "$y" -lt "${bot:-99999}" ] && return 0
        read -r x1 y1 x2 y2 < <(ui scrollbounds recyclerView); [ -n "${y2:-}" ] || return 1
        [ -n "$bot" ] && [ "$bot" -lt "$y2" ] || bot=$y2
        a shell input swipe $(((x1 + x2) / 2)) $((y1 + (bot - y1) * 3 / 4)) $(((x1 + x2) / 2)) $((y1 + (bot - y1) / 4)) 400
        sleep 0.5
    done
    return 1
}
launch
# Home → internal storage root, where Android and the test folder stand side by side.
if ! scroll_to "Interner Speicher|Internal storage" 8 scrollView || ! ui tap "Interner Speicher|Internal storage" >/dev/null ||
    ! wait_on filelist.FileListActivity 6 || ! scroll_to Android; then
    fail "the internal storage root opens"; finish
fi
if ! ui longtap "Android" >/dev/null || ! ui wait selectionCopyButton 5; then
    fail "the unreadable Android folder is selected"; finish
fi
if ! list_to ordo-e2e-unreadable || ! ui tap ordo-e2e-unreadable >/dev/null || ! ui wait "2 ausgewählt|2 selected" 5; then
    fail "the readable test folder is selected too"; finish
fi
if ! ui tap selectionCopyButton >/dev/null || ! list_to ordo-e2e-unreadable-to ||
    ! ui tap ordo-e2e-unreadable-to >/dev/null || ! ui wait action_paste 5; then
    fail "the copy target opens"; finish
fi
# The phrase stands inside the message ("„data“ kann nicht gelesen werden.\n<error>"), so it is looked for in it.
if ! ui tap action_paste >/dev/null || ! ui wait message 10 >/dev/null ||
    ! ui texts | grep -Eq "kann nicht gelesen werden|can’t be read"; then
    fail "an unreadable folder raises the error dialog"; finish
fi
pass "an unreadable folder raises the error dialog"
check_or_abort "the dialog offers retry, skip and cancel" ui has "Überspringen|Skip"
# Skipped one by one: Android/data and Android/obb each ask (A15, Android 14).
skip_all() { local _; for _ in 1 2 3 4 5; do ui has "Überspringen|Skip" button2 || return 0; ui tap button2 >/dev/null; sleep 1.5; done; }
if ! ui tap button2 >/dev/null || ! { sleep 1.5; skip_all; } ||
    ! eventually 10 a shell "test -e $TO/ordo-e2e-unreadable/src/a.txt"; then
    fail "the readable files are copied after skipping"; finish
fi
guard
pass "the readable files are copied after skipping"
check "Ordo has not crashed" test "$(crashes)" = 0
a shell "rm -rf $DIR $TO"
finish
