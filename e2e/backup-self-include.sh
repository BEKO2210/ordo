#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Backup into the folder it backs up (blocker before 1.0.0), WRITES: creates /storage/emulated/0/ordo-e2e-selfbackup
# with three files and saves the backup ZIP into that same folder, twice (the second time over the first ZIP, as
# someone who backs up into the same place every week), then removes the folder. The system file picker is driven
# (into the test folder and its Save button, as in backup-restore.sh); the ticks of the backup list are put back.
# Each time: the backup ends with "Backup finished", the ZIP still exists, is not empty, passes a full CRC test
# (Python's zipfile on the host), holds the three files, and holds no entry for itself. Regression: the target lies
# among the sources, so the backup read its own file while writing it; over an older ZIP it read the older size
# from a file it had just cut to 0, failed and deleted the target (no backup left at all).
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/ordo-e2e-selfbackup
NAME=ordo-e2e-selfbackup
tmp=$(mktemp -d)
trap 'a shell "rm -rf $DIR"; rm -rf "$tmp"' EXIT
st() { E2E_FAST=0 ui tap statusButton >/dev/null; }
picker() { case "$(top)" in com.google.android.documentsui/*|com.android.documentsui/*) ;; *) guard ;; esac; }
in_picker() { case "$(top)" in com.google.android.documentsui/*|com.android.documentsui/*) return 0 ;; esac; return 1; }
a shell "rm -rf $DIR; mkdir -p $DIR/sub && echo eins > $DIR/a.txt && echo zwei > $DIR/b.txt && head -c 300000 /dev/urandom > $DIR/sub/c.bin"
zips() { a shell "ls $DIR/*.zip 2>/dev/null" | tr -d '\r' | grep '\.zip$'; }
# zip_ok PATH: the ZIP on the device passes Python's CRC test, has the three files and no entry ending in .zip.
zip_ok() {
    rm -f "$tmp/backup.zip"
    a pull "$1" "$tmp/backup.zip" >/dev/null 2>&1 || return 1
    python3 - "$tmp/backup.zip" <<'PY'
import sys, zipfile
try:
    with zipfile.ZipFile(sys.argv[1]) as z:
        if z.testzip() is not None:
            sys.exit("CRC error")
        names = z.namelist()
except zipfile.BadZipFile as e:
    sys.exit(f"not a ZIP: {e}")
need = ("ordo-e2e-selfbackup/a.txt", "ordo-e2e-selfbackup/b.txt", "ordo-e2e-selfbackup/sub/c.bin")
missing = [n for n in need if not any(name.endswith(n) for name in names)]
itself = [n for n in names if n.lower().endswith(".zip")]
print(f"INFO ZIP entries: {len(names)}, missing: {missing}, ZIPs inside: {itself}")
sys.exit(1 if missing or itself else 0)
PY
}
# into_folder: steers the system picker into the test folder (it may open anywhere: Recent, Downloads, the last one).
into_folder() {
    local model device; model=$(a shell getprop ro.product.model | tr -d '\r')
    device=$(a shell settings get global device_name | tr -d '\r')
    ui has "$NAME" && { ui tap "$NAME" >/dev/null; sleep 2; return 0; }
    # The roots drawer: internal storage carries the device's model or device name ("Galaxy A15") or "Internal storage".
    # Samsung's picker names the drawer button "Root-Verzeichnis anzeigen" (A15, 25.11.2026).
    ui tap "Stammverzeichnisse anzeigen|Show roots|Root-Verzeichnis anzeigen|Navigationsleiste öffnen|Open navigation drawer" >/dev/null 2>&1; sleep 1.5
    ui tap "${device:-$model}|$model|Interner Speicher|Internal storage|Interner gemeinsamer Speicher|Internal shared storage" >/dev/null 2>&1 || return 1
    sleep 2
    scroll_to "$NAME" 6 >/dev/null || return 1
    ui tap "$NAME" >/dev/null; sleep 2
}

# The form scrolls behind the start bar: a row behind it is on the screen read but a tap there starts the backup, and
# a swipe starting on the bar moves nothing (A15 landscape, 25.11.2026). So swipes and taps stay above the bar.
bar_top() { ui bounds startBar | awk '{ print $2; exit }'; }
# above_bar TARGET [INSIDE]: TARGET is on screen with its middle above the start bar.
above_bar() {
    local y bar; y=$(ui bounds "$1" ${2:+"$2"} | awk '{ print int(($2 + $4) / 2); exit }'); bar=$(bar_top)
    [ -n "$y" ] && [ "$y" -lt "${bar:-99999}" ]
}
form_swipe() {
    local x1 y1 x2 y2 bot x
    read -r x1 y1 x2 y2 < <(ui scrollbounds formView); [ -n "${y2:-}" ] || return 1
    bot=$(bar_top); [ -n "$bot" ] && [ "$bot" -lt "$y2" ] || bot=$y2
    x=$(((x1 + x2) / 2))
    if [ "$1" = up ]; then a shell input swipe "$x" $((y1 + (bot - y1) * 3 / 4)) "$x" $((y1 + (bot - y1) / 4)) 400
    else a shell input swipe "$x" $((y1 + (bot - y1) / 4)) "$x" $((y1 + (bot - y1) * 3 / 4)) 400; fi
    sleep 0.5
}
# form_to TARGET [INSIDE]: scrolls the form down until TARGET stands above the bar.
form_to() { local _; above_bar "$@" && return 0; for _ in 1 2 3 4 5 6 7 8; do form_swipe up || return 1; above_bar "$@" && return 0; done; return 1; }
form_top() { local _; for _ in 1 2 3 4 5 6 7 8; do above_bar restoreRow && return 0; form_swipe down || return 1; done; }
ADD="Ordner hinzufügen|Add folder"

open_backup() { scroll_to backupCard 4 scrollView >/dev/null; ui tap backupCard >/dev/null; sleep 2.5; guard; }

launch
open_backup
check "the backup screen opens" on backup.BackupActivity
# Only the test folder: remember what was ticked and give it back at the end. The whole list is read: on a landscape
# phone none of its rows is on the first screen. The last row ("Add folder") has no tick: never read as one.
declare -A seen; ticked=()
for _ in 1 2 3 4 5 6 7 8 9 10; do
    while read -r t; do
        [ -n "$t" ] && [ -z "${seen[$t]:-}" ] || continue; seen[$t]=1
        [ "$t" != "$NAME" ] && ! [[ "|$ADD|" == *"|$t|"* ]] && ui checked "$t" && ticked+=("$t")
    done < <(ui texts | awk -F'|' '$2 == "titleText" { print $1 }')
    above_bar "$ADD" titleText && break
    form_swipe up || break
done
form_top
for t in "${ticked[@]}"; do form_to "$t" titleText && ui tap "$t" titleText >/dev/null; sleep 0.3; done
form_to "$NAME" titleText
ui checked "$NAME" || ui tap "$NAME" titleText >/dev/null
check "only the test folder is ticked" ui checked "$NAME"

# backup_once LABEL OVERWRITE: one backup into the test folder; OVERWRITE=1 picks the existing ZIP in the picker.
backup_once() {
    guard
    # "Google Drive or another place": the system picker without a starting drive. Not kept between screens.
    form_top; form_to otherRow; ui tap otherRow >/dev/null; sleep 0.5
    ui tap startButton >/dev/null; sleep 3; picker
    if ! in_picker || ! into_folder; then
        echo "SKIP $1: the system picker could not be steered into $NAME (by hand: save the backup into that folder)"
        # Back until Ordo is in front again: in the picker, back first leaves the folder it opened in (A15).
        local _; for _ in 1 2 3 4; do in_picker || break; back 1; done; sleep 2; guard; return 1
    fi
    if [ "$2" = 1 ]; then
        local old; old=$(zips | head -1)
        [ -n "$old" ] && ui tap "$(basename "$old")" >/dev/null; sleep 1.5
        # The picker asks before it replaces a file.
        ui has button1 && { ui tap button1 >/dev/null; sleep 1.5; }
    fi
    in_picker && { ui tap button1 >/dev/null; sleep 2; } # "Save", with the file name Ordo gave
    in_picker && { ui has button1 && ui tap button1 >/dev/null; sleep 2; } # "Replace" when it asks only now
    local _; for _ in $(seq 1 30); do ui has "Sicherung fertig|Backup finished|Sicherung nicht abgeschlossen|Backup not finished" statusTitle && break; sleep 1; done
    guard
    check "$1: the backup is done" ui has "Sicherung fertig|Backup finished" statusTitle
    local zip; zip=$(zips | head -1)
    echo "INFO $1: ZIPs in the folder: $(zips | tr '\n' ' ')"
    check "$1: the ZIP still exists and is not empty" a shell "test -s '${zip:-/nonexistent}'"
    check "$1: the ZIP is valid, complete and does not contain itself" zip_ok "${zip:-/nonexistent}"
    check "$1: exactly one ZIP in the folder" test "$(zips | wc -l)" = 1
    st; sleep 2 # "Done" closes the screen
    on backup.BackupActivity || open_backup
}
if backup_once "first backup" 0; then
    backup_once "second backup over the first" 1
fi

# The ticks as they were; the test folder goes, so its entry leaves the list by itself.
on backup.BackupActivity || open_backup
form_top; form_to "$NAME" titleText
ui checked "$NAME" && ui tap "$NAME" titleText >/dev/null; sleep 0.3
form_top
for t in "${ticked[@]}"; do form_to "$t" titleText && ui tap "$t" titleText >/dev/null; sleep 0.3; done
all_ticked() { local t; form_top; for t in "${ticked[@]}"; do form_to "$t" titleText; ui checked "$t" || return 1; done; }
check "the ticks are back as they were" all_ticked
back 2
finish
