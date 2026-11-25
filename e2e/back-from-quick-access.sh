#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# farm: orient=once
# Regression (0.18.1): a quick-access folder (Pictures), then a subfolder, then back left the list for the home screen.
# Back walks up to the folder the tile opened, and only back there returns home.
# WRITES: creates /sdcard/Pictures/ordo-e2e-back (with one subfolder) and removes only that at the end.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/sdcard/Pictures/ordo-e2e-back
SUB=ordo-e2e-sub
trap 'a shell rm -rf "$DIR" >/dev/null' EXIT
# The Pictures tile is hidden while the folder is empty: the test folder keeps it on screen.
a shell mkdir -p "$DIR/$SUB"
launch
# tap_in LIST TARGET: tap the TARGET text that lies inside LIST ("Bilder" is also a category card).
tap_in() {
    local l t x1 y1 x2 y2
    read -r _ l _ t < <(ui bounds "$1" | head -1) || return 1
    read -r x1 y1 x2 y2 < <(ui bounds "$2" nameText | awk -v l="$l" -v t="$t" '$2 >= l && $4 <= t' | head -1)
    [ -n "${y2:-}" ] && a shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}
check_or_abort "the Pictures tile is on the home screen" scroll_to quickAccessList
check_or_abort "the Pictures tile opens the file list" eval 'tap_in quickAccessList "Bilder|Pictures" && wait_on filelist.FileListActivity 6'
guard
check_or_abort "the test folder is listed in Pictures" ui see ordo-e2e-back nameText
ui tap ordo-e2e-back nameText >/dev/null
check_or_abort "the subfolder opens" ui see "$SUB" nameText
ui tap "$SUB" nameText >/dev/null
sleep 1.5; guard
back
check "back from the subfolder stays in the file list" on filelist.FileListActivity
check "back from the subfolder shows its parent" ui see "$SUB" nameText
back
check "back from the test folder shows Pictures" ui see ordo-e2e-back nameText
back 2
check "back on Pictures, where the tile opened, returns home" on home.HomeActivity
finish
