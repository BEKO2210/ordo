#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# The home screen's search bar at the bottom (Settings > Appearance): it moves without a restart, sits above the
# navigation bar, the end of the page scrolls clear of it, and every control in it still works. Switches the setting
# to "bottom" and back to what it was before; otherwise read-only.
. "$(dirname "$0")/lib.sh" "$@"
# The bar moves, and the "more" popup with it: remembered tap spots would hit the old place.
export E2E_FAST=0
launch
read -r W H < <(screen_size)
home() { on home.HomeActivity; }
TITLE="Suchleiste der Startseite|Home search bar"
BOTTOM="Unten – mit dem Daumen erreichbar|Bottom – within thumb reach"
TOP="Oben|Top"
bar_top() { ui bounds searchCard | awk 'NR == 1 { print $2 }'; }
at_bottom() { local y; y=$(bar_top); [ -n "$y" ] && [ "$y" -gt $((H / 2)) ]; }
sheet_closed() { ! ui has "headerOrb|setupLayout"; }
menu_closed() { ! ui visible navigationFragment; }
at_top() { local y; y=$(bar_top); [ -n "$y" ] && [ "$y" -lt $((H / 2)) ]; }
appearance() {
    guard; ui tap moreButton >/dev/null 2>&1 || return 1
    ui tap "Einstellungen|Settings" title >/dev/null || return 1; guard
    ui tap "Aussehen|Appearance" >/dev/null || return 1; sleep 1.5; guard
}
# position ENTRY: picks the entry in the settings and returns to the home screen. The row and, on a short
# landscape phone, the dialog row start below the window. A missed tap is a failure of the caller, not a success.
position() {
    local ok=0
    if appearance && scroll_to "$TITLE" && ui tap "$TITLE" >/dev/null &&
        ui wait choice_root 6 >/dev/null && scroll_choice "$1" &&
        { ui tap "$1" choice_root >/dev/null 2>&1 || ui tap "$1" choice_label >/dev/null; } &&
        ui waitgone choice_root 6 >/dev/null; then
        ok=1
    else
        ui has choice_root && back
    fi
    for _ in 1 2 3; do home && break; guard; back; done
    [ "$ok" = 1 ] && home
}

if ! { appearance && scroll_to "$TITLE"; }; then
    fail "the home search bar setting is on the appearance page"
    for _ in 1 2 3; do home && break; guard; back; done
    finish
fi
ui has "$BOTTOM"; was_bottom=$?
for _ in 1 2 3; do home && break; guard; back; done
check "the search bar moves to the bottom without a restart" eval 'position "$BOTTOM" && at_bottom'
# Without the navigation bar inset the card would end its own 8 dp margin above the screen edge.
read -r _ _ _ card_end < <(ui bounds searchCard)
margin=$(( $(a shell wm density | tail -1 | sed 's/.*: //') * 8 / 160 ))
check "it floats above the navigation bar" test $((H - ${card_end:-$H})) -gt $((margin + 2))

# The last section ends above the bar, so the last tiles stay reachable.
# Swipes until the page stops moving: four left a landscape phone short of the end (A15, 25.11.2026).
swipes=0 was=""
while [ "$swipes" -lt 12 ]; do
    swipe_up; swipes=$((swipes + 1))
    now=$(ui bounds "quickAccessList|storageList"); [ "$now" = "$was" ] && break; was=$now
done
last=$(ui bounds "quickAccessList|storageList" | awk '$4 > m { m = $4 } END { print m + 0 }')
check "scrolled to the end, the last tiles end above the bar" test "$last" -le "$(bar_top)"
for _ in $(seq 1 "$swipes"); do swipe_down; done

ui tap searchCard >/dev/null
check "the search opens from the bottom bar" wait_on filelist.FileListActivity 5; guard
for _ in 1 2 3; do home && break; guard; back; done
check "back returns to the home screen" home

ui tap assistantButton >/dev/null; sleep 2; guard
check_wand
back
ui has headerOrb && back   # the first back only hid the keyboard
check "back closes the command sheet" sheet_closed

guard; ui tap moreButton >/dev/null 2>&1
check "more opens its menu" ui see "Zuletzt verwendet|Recent files" title
back 1

ui tap menuButton >/dev/null 2>&1; sleep 1
check "the menu icon opens the side menu" ui visible navigationFragment
back
check "back closes the side menu" menu_closed

# Restore what the owner had.
if [ "$was_bottom" = 0 ]; then
    check "the setting is still bottom, as before the test" at_bottom
else
    check "back at the top, as before the test" eval 'position "$TOP" && at_top'
fi
finish
