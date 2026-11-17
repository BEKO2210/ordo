#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# With files selected, "More" in the bar at the bottom opens its menu right above that bar, on the button's side,
# not at the top of the screen. Read-only: selects the first entry of internal storage and closes the menu and the selection again.
. "$(dirname "$0")/lib.sh" "$@"
export E2E_FAST=0
launch
read -r W H < <(screen_size)
# Internal storage's top folder: it has entries on every device (Downloads may be empty, as on a fresh tablet).
a shell am start -W -a android.intent.action.VIEW -d "file:///storage/emulated/0" -t inode/directory \
    -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
sleep 4; guard
first=$(ui texts | grep -m1 '|nameText|' | cut -d'|' -f1)
ui longtap "$first" >/dev/null; sleep 1.5; guard
read -r mx1 my1 mx2 _ < <(ui bounds selectionMoreButton | head -1)
ui tap selectionMoreButton >/dev/null; sleep 1.2; guard
# The last entry of the menu, "Select all", sits just above the bar.
read -r sx1 _ sx2 sy2 < <(ui bounds "Alles auswählen|Select all" | head -1)
check "the menu opens in the lower half" test "${sy2:-0}" -gt $((H / 2))
check "the menu ends at the bar, not below it" test "${sy2:-0}" -le "${my1:-0}"
# On the button's side: in LTR the menu's right edge and the button's right edge are on the same half.
mid() { echo $((($1 + $2) / 2)); }
check "the menu is on the side of the More button" \
    test $(( $(mid "${sx1:-0}" "${sx2:-0}") > W / 2 )) = $(( $(mid "${mx1:-0}" "${mx2:-0}") > W / 2 ))
back; back
finish
