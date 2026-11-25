#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Backup into the folder it backs up (blocker before 1.0.0), WRITES: creates /storage/emulated/0/ordo-e2e-selfbackup
# with three files and saves the backup ZIP into that same folder, twice (the second time over the first ZIP, as
# someone who backs up into the same place every week: Ordo then asks for a new name and keeps the older backup, GB-10),
# then removes the folder. The system file picker is driven
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
    local model; model=$(a shell getprop ro.product.model | tr -d '\r')
    ui has "$NAME" && { ui tap "$NAME" >/dev/null; sleep 2; return 0; }
    # The roots drawer: internal storage carries the device's model name (or "Internal storage").
    ui tap "Stammverzeichnisse anzeigen|Show roots|Navigationsleiste öffnen|Open navigation drawer" >/dev/null 2>&1; sleep 1.5
    ui tap "$model|Interner Speicher|Internal storage|Interner gemeinsamer Speicher|Internal shared storage" >/dev/null 2>&1 || return 1
    sleep 2
    scroll_to "$NAME" 6 >/dev/null || return 1
    ui tap "$NAME" >/dev/null; sleep 2
}

open_backup() { scroll_to backupCard 4 >/dev/null; ui tap backupCard >/dev/null; sleep 2.5; guard; }

launch
open_backup
check "the backup screen opens" on backup.BackupActivity
# Only the test folder: remember what was ticked and give it back at the end.
mapfile -t ticked < <(ui texts | awk -F'|' '$2 == "titleText" { print $1 }' | while read -r t; do
    [ "$t" != "$NAME" ] && ui checked "$t" && echo "$t"; done)
for t in "${ticked[@]}"; do ui tap "$t" titleText >/dev/null; sleep 0.3; done
scroll_to "$NAME" 4 >/dev/null
ui checked "$NAME" || ui tap "$NAME" titleText >/dev/null
check "only the test folder is ticked" ui checked "$NAME"

# backup_once LABEL OVERWRITE: one backup into the test folder; OVERWRITE=1 picks the existing ZIP in the picker.
backup_once() {
    guard
    # "Google Drive or another place": the system picker without a starting drive. Not kept between screens.
    scroll_to otherRow 4 >/dev/null; ui tap otherRow >/dev/null; sleep 0.5
    scroll_to startButton 3 >/dev/null
    ui tap startButton >/dev/null; sleep 3; picker
    if ! in_picker || ! into_folder; then
        echo "SKIP $1: the system picker could not be steered into $NAME (by hand: save the backup into that folder)"
        in_picker && back 1; sleep 2; guard; return 1
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
    if [ "$2" = 1 ]; then
        # GB-10 (stabil-p3): Ordo writes nothing into a document that already holds data; it asks for a new name and
        # the older backup stays as it was.
        check "$1: Ordo asks for a new name instead of overwriting" \
            eval 'ui texts | grep -qE "schon eine Sicherung oder eine andere Datei|already a backup or another file"'
        check "$1: the older backup is unchanged" test "$(a shell "md5sum '$OLD_ZIP'" | awk '{print $1}')" = "$OLD_SUM"
    else
        check "$1: the backup is done" ui has "Sicherung fertig|Backup finished" statusTitle
    fi
    local zip; zip=$(zips | head -1)
    echo "INFO $1: ZIPs in the folder: $(zips | tr '\n' ' ')"
    check "$1: the ZIP still exists and is not empty" a shell "test -s '${zip:-/nonexistent}'"
    check "$1: the ZIP is valid, complete and does not contain itself" zip_ok "${zip:-/nonexistent}"
    check "$1: exactly one ZIP in the folder" test "$(zips | wc -l)" = 1
    OLD_ZIP=$zip; OLD_SUM=$(a shell "md5sum '${zip:-/nonexistent}'" | awk '{print $1}')
    st; sleep 2 # "Done" closes the screen
    on backup.BackupActivity || open_backup
}
if backup_once "first backup" 0; then
    backup_once "second backup over the first" 1
fi

# The ticks as they were; the test folder goes, so its entry leaves the list by itself.
on backup.BackupActivity || open_backup
scroll_to "$NAME" 4 >/dev/null
ui checked "$NAME" && ui tap "$NAME" titleText >/dev/null; sleep 0.3
swipe_down; swipe_down
for t in "${ticked[@]}"; do scroll_to "$t" 4 >/dev/null; ui tap "$t" titleText >/dev/null; sleep 0.3; done
all_ticked() { local t; for t in "${ticked[@]}"; do ui checked "$t" || return 1; done; }
check "the ticks are back as they were" all_ticked
back 2
finish
