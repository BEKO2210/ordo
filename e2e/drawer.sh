#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# The side menu on the home screen (0.5.0): every entry opens its screen, and back returns to the home screen with
# the menu closed. Regression (0.5.0): a menu dragged all the way open was never registered as open, so a tap
# beside it did nothing and back left the app. Read-only.
. "$(dirname "$0")/lib.sh" "$@"
launch
read -r W H < <(screen_size)
# The side menu lives in the activity's window, so the fast view-tree check is exact for it (0.2 s, not 2.4 s).
menu_open() { ui visible navigationFragment; }
menu_closed() { ! menu_open; }
open_menu() { ui tap menuButton >/dev/null 2>&1; sleep 1; }
# Top edge of the navigation bar at the bottom (gesture bar or buttons), else the screen's end. A tap on an entry
# behind it reaches the system, not the menu: on a landscape tablet the last visible entry sat right there.
NAV=$(a shell dumpsys window | grep -m1 -oE "type=navigationBars frame=\[0,[0-9]+\]\[$W,$H\]" | sed -E 's/.*\[0,([0-9]+)\].*/\1/')
NAV=${NAV:-$H}
# tap_entry ENTRY: taps it only when it lies fully above the navigation bar.
tap_entry() {
    local x1 y1 x2 y2
    read -r x1 y1 x2 y2 < <(ui bounds "$1" titleText | head -1)
    [ -n "${y2:-}" ] && [ "$y2" -le "$NAV" ] || return 1
    a shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2)); sleep 1.2
}
# The menu is a list: its last entries are only reachable by scrolling.
menu_scroll() { a shell input swipe $((W / 6)) $((H * $1 / 100)) $((W / 6)) $((H * $2 / 100)) 400; sleep 1; }
menu_top() { menu_scroll 30 80; menu_scroll 30 80; }
titles() { ui texts | awk -F'|' '$2 == "titleText" { print $1 }'; }
# Taps an entry by its title; the same word may stand on the page behind the menu, so only titles of the menu count.
# above ENTRY VISIBLE: true when ENTRY comes before the first visible title in the menu's order.
above() {
    local i first=-1 want=-1
    for i in "${!entries[@]}"; do
        [ "${entries[$i]}" = "$1" ] && want=$i
        [ "$first" = -1 ] && grep -qxF -- "${entries[$i]}" <<<"$2" && first=$i
    done
    [ "$want" -ge 0 ] && [ "$first" -ge 0 ] && [ "$want" -lt "$first" ]
}
# Entries are opened in order, so look where the menu stands first and further down next; only then from the top.
menu_tap() {
    local round try
    # The wanted entry lies above everything on screen: start from the top right away.
    local seen
    seen=$(titles)
    grep -qxF -- "$1" <<<"$seen" || ! above "$1" "$seen" || menu_top
    for round in 1 2; do
        # Small steps: a long swipe can fling an entry past the visible area on tall phones.
        for try in 1 2 3 4 5 6 7 8; do
            tap_entry "$1" && return 0
            menu_scroll 65 50
        done
        menu_top
    done
    return 1
}

open_menu
check "the menu icon opens the side menu" menu_open
menu_top
# Until the rows on screen stop changing: that is the end. Two swipes on a landscape phone never get there,
# and the tools then look like whatever happened to be last.
prev=""
collected=""
started=0
for _ in $(seq 1 24); do
    now=$(titles)
    # The first read has nothing to compare with; an empty dump must not look like the end.
    [ "$started" = 1 ] && [ "$now" = "$prev" ] && break
    started=1
    prev=$now
    collected=$(printf '%s\n%s\n' "$collected" "$now" | awk 'NF && !seen[$0]++')
    menu_scroll 65 50
done
entries=()
mapfile -t entries < <(printf '%s\n' "$collected" | awk 'NF')
back
check "back closes the side menu" menu_closed
check "back stays on the home screen" on home.HomeActivity

# Ordo's own apps come first (21.11.), then storages and folders that differ per device; the nine tools at the end
# are fixed. The screen comes from the title: a short list used to treat its last rows as these tools.
apps=(viewer.audio.MusicLauncherActivity viewer.video.VideoLauncherActivity)
tools=(apps.AppsActivity analysis.AnalysisActivity trash.TrashActivity vault.VaultActivity backup.BackupActivity
    ftpserver.FtpServerActivity settings.SettingsActivity about.AboutActivity legal.LegalActivity)
tool_titles=("Apps" "Speicher analysieren|Analyze storage" "Papierkorb|Trash" "Tresor|Vault" "Sicherung|Backup"
    "FTP-Server|FTP server" "Einstellungen|Settings" "Über|About" "Impressum & Datenschutz|Legal notice & privacy")
count=${#entries[@]}
menu_complete() {
    local spec alt found
    local -a alts
    [ "$count" -gt $((${#apps[@]} + ${#tools[@]})) ] || return 1
    for spec in "${tool_titles[@]}"; do
        found=1
        IFS='|' read -ra alts <<<"$spec"
        for alt in "${alts[@]}"; do
            printf '%s\n' "${entries[@]}" | grep -qxF -- "$alt" && { found=0; break; }
        done
        [ "$found" = 0 ] || return 1
    done
}
tool_screen() {
    local alt i
    local -a alts
    for i in "${!tools[@]}"; do
        IFS='|' read -ra alts <<<"${tool_titles[$i]}"
        for alt in "${alts[@]}"; do
            [ "$1" = "$alt" ] && { echo "${tools[$i]}"; return 0; }
        done
    done
    return 1
}
check "the menu lists the apps, a storage and the nine tools" menu_complete
check "Ordo Musik and Ordo Video come first" test "${entries[0]:-}|${entries[1]:-}" = "Ordo Musik|Ordo Video" -o \
    "${entries[0]:-}|${entries[1]:-}" = "Ordo Music|Ordo Video"
for i in "${!entries[@]}"; do
    entry=${entries[$i]}
    if screen=$(tool_screen "$entry"); then :
    elif [ "$i" -lt "${#apps[@]}" ]; then screen=${apps[$i]}
    elif [[ $entry == *… ]]; then screen=storage.AddStorageDialogActivity
    else screen=filelist.FileListActivity; fi
    guard
    open_menu
    if ! menu_tap "$entry"; then fail "$entry: not found in the menu"; back; continue; fi
    wait_on "$screen"; guard
    check "$entry opens ${screen##*.}" on "$screen"
    back 0.3
    # An unlock prompt was on top: one more back closes it, and one before that its keyboard (PIN or password).
    for _ in 1 2; do wait_on home.HomeActivity 3 && break; guard; back 2; done
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
