#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# The side menu on the home screen (0.5.0): every entry opens its screen, and back returns to the home screen with
# the menu closed. Regression (0.5.0): a menu dragged all the way open was never registered as open, so a tap
# beside it did nothing and back left the app. Read-only.
. "$(dirname "$0")/lib.sh" "$@"
launch
read -r W H < <(screen_size)
menu_open() { ui has navigationFragment; }
menu_closed() { ! menu_open; }
open_menu() { ui tap menuButton >/dev/null 2>&1; sleep 1; }
# The menu is a list: its last entries are only reachable by scrolling.
menu_scroll() { a shell input swipe $((W / 6)) $((H * $1 / 100)) $((W / 6)) $((H * $2 / 100)) 400; sleep 1; }
menu_top() { menu_scroll 30 80; menu_scroll 30 80; }
titles() { ui texts | awk -F'|' '$2 == "titleText" { print $1 }'; }
# Taps an entry by its title; the same word may stand on the page behind the menu, so only titles of the menu count.
menu_tap() {
    local try
    menu_top
    for try in 1 2 3 4; do
        ui tap "$1" titleText >/dev/null 2>&1 && return 0
        menu_scroll 70 40
    done
    return 1
}

open_menu
check "the menu icon opens the side menu" menu_open
menu_top
mapfile -t entries < <({ titles; menu_scroll 70 40; titles; menu_scroll 70 40; titles; } | awk 'NF && !seen[$0]++')
back
check "back closes the side menu" menu_closed
check "back stays on the home screen" on home.HomeActivity

# Storages and folders come first and differ per device; the nine tools at the end are fixed.
tools=(apps.AppsActivity analysis.AnalysisActivity trash.TrashActivity vault.VaultActivity backup.BackupActivity
    ftpserver.FtpServerActivity settings.SettingsActivity about.AboutActivity legal.LegalActivity)
count=${#entries[@]}
check "the menu lists a storage and the nine tools" test "$count" -gt "${#tools[@]}"
for i in "${!entries[@]}"; do
    entry=${entries[$i]}
    tool=$((i - count + ${#tools[@]}))
    if [ "$tool" -ge 0 ]; then screen=${tools[$tool]}
    elif [[ $entry == *… ]]; then screen=storage.AddStorageDialogActivity
    else screen=filelist.FileListActivity; fi
    guard
    open_menu
    if ! menu_tap "$entry"; then fail "$entry: not found in the menu"; back; continue; fi
    sleep 2; guard
    check "$entry opens ${screen##*.}" on "$screen"
    back 2
    on home.HomeActivity || back 2   # an unlock prompt was on top
    check "$entry: back returns to the home screen" on home.HomeActivity
    # A dialog opens over the menu, which stays open below it, as in the file list.
    [ "$screen" = storage.AddStorageDialogActivity ] && menu_open && back
    check "$entry: the side menu is closed" menu_closed
done

# Dragged further than the menu is wide.
y=$((H * 6 / 10))
drag_open() { guard; a shell input swipe $((W / 8)) $y $((W * 97 / 100)) $y 500; sleep 1.5; }
drag_open
check "a long drag opens the side menu" menu_open
a shell input tap $((W * 9 / 10)) $y; sleep 1.5
check "a tap beside it closes it" menu_closed
drag_open
back
check "back closes it instead of leaving the app" on home.HomeActivity
check "the side menu is closed after back" menu_closed
finish
