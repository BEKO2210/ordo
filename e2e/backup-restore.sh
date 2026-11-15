#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Backup and restore (0.7.0), WRITES: creates /sdcard/ordo-e2e-restore and an encrypted backup ZIP on the SD card,
# removes both at the end. Needs a device with an SD card; the system file picker is driven too.
# Regressions: an encrypted backup was always "damaged" on restore (sizes stand after the data in a streamed ZIP);
# restoring twice added a second " (restored 2)" copy; a folder added to the list could not be removed; turning the
# device while the system picker was open dropped the backup with "choose at least one folder".
. "$(dirname "$0")/lib.sh" "$@"
DIR=/sdcard/ordo-e2e-restore
PW=e2eRichtig7
# The status button moves with the length of the message above it: never tap it from the remembered spot.
st() { E2E_FAST=0 ui tap statusButton >/dev/null; }
picker() { case "$(top)" in com.google.android.documentsui/*|com.android.documentsui/*) ;; *) guard ;; esac; }
sd=$(a shell "ls -d /storage/*-*" 2>/dev/null | head -1 | tr -d '\r')
[ -n "$sd" ] || { echo "SKIP: no SD card"; exit 0; }
a shell "mkdir -p $DIR/sub && echo eins > $DIR/a.txt && echo zwei > $DIR/b.txt && head -c 300000 /dev/urandom > $DIR/sub/c.bin"
sums() { a shell "cd $DIR && md5sum a.txt sub/c.bin" | awk '{print $1}' | tr '\n' ' '; }
before=$(sums); b_sum=$(a shell "md5sum $DIR/b.txt" | awk '{print $1}')
zip=$sd/Ordo-Sicherung-$(date +%Y-%m-%d).zip
a shell "rm -f '$zip'"

launch
ui tap Sicherung >/dev/null; sleep 2.5; guard
check "the backup screen opens" on backup.BackupActivity
# Only the test folder: remember what was ticked and give it back at the end.
mapfile -t ticked < <(ui texts | awk -F'|' '$2 == "titleText" { print $1 }' | while read -r t; do
    [ "$t" != ordo-e2e-restore ] && ui checked "$t" && echo "$t"; done)
for t in "${ticked[@]}"; do ui tap "$t" titleText >/dev/null; sleep 0.3; done
ui checked ordo-e2e-restore || ui tap ordo-e2e-restore titleText >/dev/null
ui tap sdRow >/dev/null; sleep 0.5
ui tap encryptRow >/dev/null; sleep 1
ui tap passwordEdit >/dev/null; a shell input text $PW
ui tap passwordRepeatEdit >/dev/null; a shell input text $PW; a shell input keyevent 111; sleep 0.5
ui tap startButton >/dev/null; sleep 3; picker
# Turned while the picker is open: the backup screen is made anew behind it and must still start the backup.
rotation=$(a shell settings get system user_rotation | tr -d '\r'); auto=$(a shell settings get system accelerometer_rotation | tr -d '\r')
a shell settings put system accelerometer_rotation 0; a shell settings put system user_rotation 1; sleep 3; picker
ui tap button1 >/dev/null; sleep 6   # the system picker's "save", file name given by Ordo
a shell settings put system user_rotation "${rotation:-0}"; a shell settings put system accelerometer_rotation "${auto:-1}"
sleep 3; guard
check "the backup is done" ui has "Sicherung fertig|Backup done" statusTitle
check "the backup ZIP is on the SD card" a shell "test -s '$zip'"
st; sleep 2   # "done" closes the screen
ui tap Sicherung >/dev/null; sleep 2.5; guard

a shell "rm $DIR/a.txt $DIR/sub/c.bin; echo geaendert-laenger > $DIR/b.txt"
restore() {   # restore PASSWORD
    ui tap "ZIP-Sicherung auswählen|Choose ZIP backup" >/dev/null; sleep 3; picker
    ui tap "$(basename "$zip")" title >/dev/null; sleep 3; guard
    a shell input text "$1"; ui tap button1 >/dev/null; sleep 5; guard
}
restore falsch123
check "a wrong password is told apart" ui has "Passwort erneut eingeben|Enter password again" statusButton
check "a wrong password writes nothing" a shell "test ! -e $DIR/a.txt"
st; sleep 2
a shell "input keyevent 123; for i in \$(seq 12); do input keyevent 67; done"
a shell input text $PW; ui tap button1 >/dev/null; sleep 5; guard
check "the right password shows the preview" ui has "Bereit zum Wiederherstellen|Ready to restore" statusTitle
st; sleep 6; guard
check "the restore is done" ui has "Wiederherstellung fertig|Restore done" statusTitle
check "the restored files are the backed up ones" test "$(sums)" = "$before"
check "the changed file is never replaced" test "$(a shell "cat $DIR/b.txt" | tr -d '\r')" = geaendert-laenger
check "its backed up version comes back beside it" \
    test "$(a shell "md5sum '$DIR/b (restored).txt'" | awk '{print $1}')" = "$b_sum"
st; sleep 2
ui tap Sicherung >/dev/null; sleep 2.5; guard
restore $PW; st; sleep 6; guard
check "restoring twice adds no second copy" a shell "test ! -e '$DIR/b (restored 2).txt'"
st; sleep 2

# The ticks as they were; the test folder goes, so its entry leaves the list by itself.
ui tap Sicherung >/dev/null; sleep 2.5; guard
ui tap ordo-e2e-restore titleText >/dev/null; sleep 0.3
for t in "${ticked[@]}"; do ui tap "$t" titleText >/dev/null; sleep 0.3; done
all_ticked() { local t; for t in "${ticked[@]}"; do ui checked "$t" || return 1; done; }
check "the ticks are back as they were" all_ticked
back 2
a shell "rm -rf $DIR; rm -f '$zip'"
finish
