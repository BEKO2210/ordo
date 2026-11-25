#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Storage analysis (0.6.0): the scan ends in the overview, the ring, the folder map and the rows lead where they
# say, and back goes down one step at a time: a marked chart element, then a page, then home. Read-only; it turns
# the screen once and puts the rotation settings back.
. "$(dirname "$0")/lib.sh" "$@"
OVERVIEW=usageHero
HINT="Kachel antippen für Details|Tap a tile for details"
not() { ! "$@"; }
# tap_in ID FX FY: tap inside a view, at fractions of its width and height.
tap_in() {
    read -r L T R B < <(ui bounds "$1") || return 1
    a shell input tap $((L + (R - L) * $2 / 100)) $((T + (B - T) * $3 / 100)); sleep 1.5
}
# Brings a node on screen, scrolling down a few times.
reach() { for _ in 1 2 3 4 5 6; do ui has "$1" && return 0; swipe_up; done; return 1; }
top_of_page() { for _ in 1 2 3 4; do swipe_down; done; }
DPI=$(a shell wm density | tail -1 | grep -o '[0-9]*$')
# whole ID DP: the view is on screen with at least DP of height, so not cut off by the edge of the screen.
whole() { read -r L T R B < <(ui bounds "$1") && [ $((B - T)) -ge $(($2 * DPI / 160)) ]; }
# Scrolls in small steps until the view is whole.
settle() {
    read -r W H < <(screen_size)
    for _ in 1 2 3 4 5 6 7 8; do
        whole "$1" "$2" && return 0
        a shell input swipe $((W / 2)) $((H * 6 / 10)) $((W / 2)) $((H * 4 / 10)) 400; sleep 1.2
    done
    return 1
}
# first_of NAME...: the first of these texts that is on screen.
first_of() { for name in "$@"; do ui has "$name" && { echo "$name"; return 0; }; done; return 1; }

# in_reach ID: scrolled on until the view's middle is above the lowest tenth of the screen. On a landscape tablet with
# button navigation the button was in the dump but behind the navigation bar, and the tap missed (W90, a15g-5).
in_reach() {
    local i _ t _ b H
    read -r _ H < <(screen_size)
    for i in 1 2 3; do
        read -r _ t _ b < <(ui bounds "$1" | head -1)
        [ -n "$b" ] && [ $(((t + b) / 2)) -lt $((H * 9 / 10)) ] && return 0
        scroll_in up scrollView || return 1
    done
    return 1
}
launch
# One swipe leaves a landscape phone on home. Later rows share names with home, so stop when the screen never changed.
if scroll_to analyzeButton 8 scrollView && in_reach analyzeButton && ui tap analyzeButton >/dev/null 2>&1 &&
    wait_on analysis.AnalysisActivity 4; then
    pass "analysis opens"
else
    fail "analysis opens"
    finish
fi
guard
check "the scan ends in the overview" ui wait "$OVERVIEW" 120
sleep 2
check "the ring of your files is there" settle filesChart 200

# --- Rows of the storage card
top_of_page
if ui tap "System & App-Daten|System & app data" >/dev/null 2>&1; then
    sleep 2; guard
    check "system row opens the app list" on apps.AppsActivity
    back 2
    check "back from the app list shows the overview" ui has "$OVERVIEW"
else
    fail "system row not found"
fi

# --- The ring: a tap lifts a slice, back drops it; a second tap opens the type
if settle filesChart 200 && tap_in filesChart 50 93; then
    guard
    check "a tap on the ring stays on the overview" ui has filesChart
    back
    check "back drops the lifted slice and stays in the analysis" on analysis.AnalysisActivity
    tap_in filesChart 50 93; tap_in filesChart 50 93; guard
    check "a second tap on the slice opens its page" not ui has filesChart
    back
    check "back from the type page shows the overview" ui has filesChart
    back
    check "back drops the slice, still in the analysis" on analysis.AnalysisActivity
else
    fail "ring not on screen"
fi

# --- A type row: page, selection, rotation, back
swipe_up
kind=$(first_of "Videos" "Bilder|Pictures" "Dokumente|Documents" "Audio" "Andere Dateien|Other files")
if [ -n "$kind" ] && ui tap "$kind" >/dev/null 2>&1; then
    check "type row opens its page" ui see nameText; guard
    ui longtap nameText >/dev/null 2>&1; guard
    check "long press selects a file" ui has deleteButton
    back
    check "back clears the selection first" not ui has deleteButton
    check "and stays on the page" ui has nameText
    rotation=$(a shell settings get system user_rotation | tr -d '\r'); auto=$(a shell settings get system accelerometer_rotation | tr -d '\r')
    a shell settings put system accelerometer_rotation 0; a shell settings put system user_rotation 1; sleep 4; guard # deliberate: the rotation and the page built anew
    check "rotation keeps the page" ui has nameText
    a shell settings put system user_rotation "${rotation:-0}"; a shell settings put system accelerometer_rotation "${auto:-1}"; sleep 4; guard # deliberate: as above
    check "turning back keeps the page" ui has nameText
    back
    check "back from the type page leaves the list" not ui has nameText
    check "and is still the analysis" on analysis.AnalysisActivity
else
    fail "no file type row found"
fi

# --- The folder map: a tap marks a tile and names it below, a second tap opens it
if reach "$HINT"; then
    tap_in folderMap 15 30; guard
    check "a tap on a tile names it below the map" not ui has "$HINT"
    tap_in folderMap 15 30; guard
    check "a second tap opens the folder" not ui has folderMap
    back
    check "back from the folder page shows the map" ui has folderMap
    back
    check "back unmarks the tile" ui has "$HINT"
else
    echo "SKIP folder map: this storage has one folder only"
fi

# --- A folder row
reach folderList >/dev/null; swipe_up
sleep 1.5   # a tap while the page still flings only stops it (Tab S9 Ultra)
folder=$(first_of "DCIM" "Download" "Pictures" "Movies" "Music" "Documents")
if [ -n "$folder" ] && ui tap "$folder" >/dev/null 2>&1; then
    sleep 1; guard
    # The folder page lists its subfolders with the same row id, so check that the overview's card is gone.
    check "folder row opens its page" not ui has folderCard
    back
    check "back from the folder page shows the rows" ui has folderCard
else
    fail "no folder row found"
fi

back 2
check "back from the overview returns to home" on home.HomeActivity
finish
