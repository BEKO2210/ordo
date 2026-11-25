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
# A run that stops inside the system picker must not leave it in front for the farm's next suite (a15g-5).
trap 'for _ in 1 2 3 4; do [[ $(top) == *documentsui/* ]] || break; a shell input keyevent KEYCODE_BACK; sleep 1; done' EXIT
# wait_picker: up to 5 s for the system picker to be in front, then the check above.
wait_picker() {
    local end=$((SECONDS + 5))
    until [[ $(top) == *documentsui/* ]]; do [ $SECONDS -ge $end ] && break; sleep 0.3; done; picker
}
export E2E_SEE=12 # writing and reading a backup takes longer than a screen change
sd=$(a shell "ls -d /storage/*-*" 2>/dev/null | head -1 | tr -d '\r')
[ -n "$sd" ] || { echo "SKIP: no SD card"; exit 0; }
a shell "mkdir -p $DIR/sub && echo eins > $DIR/a.txt && echo zwei > $DIR/b.txt && head -c 300000 /dev/urandom > $DIR/sub/c.bin"
sums() { a shell "cd $DIR && md5sum a.txt sub/c.bin" | awk '{print $1}' | tr '\n' ' '; }
before=$(sums); b_sum=$(a shell "md5sum $DIR/b.txt" | awk '{print $1}')
zip=$sd/Ordo-Sicherung-$(date +%Y-%m-%d).zip
a shell "rm -f '$zip'"

launch
ui tap Sicherung >/dev/null
check "the backup screen opens" wait_on backup.BackupActivity 4; guard
# Only the test folder: remember what was ticked and give it back at the end.
mapfile -t ticked < <(ui texts | awk -F'|' '$2 == "titleText" { print $1 }' | while read -r t; do
    [ "$t" != ordo-e2e-restore ] && ui checked "$t" && echo "$t"; done)
for t in "${ticked[@]}"; do ui tap "$t" titleText >/dev/null; done
ui checked ordo-e2e-restore || ui tap ordo-e2e-restore titleText >/dev/null
ui tap sdRow >/dev/null
# On a short screen (W90) the protection card starts under the bottom bar: a tap on its visible sliver hit the
# system's Home button. Scrolled into view first, and again once the password fields are open.
swipe_up; swipe_up
ui tap encryptRow >/dev/null
swipe_up
ui tap passwordEdit >/dev/null; a shell input text $PW
ui tap passwordRepeatEdit >/dev/null; a shell input text $PW; a shell input keyevent 111; sleep 0.5
ui tap startButton >/dev/null; wait_picker
# Turned while the picker is open: the backup screen is made anew behind it and must still start the backup.
rotation=$(a shell settings get system user_rotation | tr -d '\r'); auto=$(a shell settings get system accelerometer_rotation | tr -d '\r')
a shell settings put system accelerometer_rotation 0; a shell settings put system user_rotation 1; sleep 3; picker # deliberate: the rotation, with the picker rebuilt
E2E_TAP_ANY=1 ui tap button1 >/dev/null; wait_on backup.BackupActivity 8   # the system picker's "save", file name given by Ordo
a shell settings put system user_rotation "${rotation:-0}"; a shell settings put system accelerometer_rotation "${auto:-1}"
sleep 1 # deliberate: the rotation back
check "the backup is done" ui see "Sicherung fertig|Backup done" statusTitle; guard
check "the backup ZIP is on the SD card" a shell "test -s '$zip'"
st   # "done" closes the screen
ui tap Sicherung >/dev/null; wait_on backup.BackupActivity 5; guard

a shell "rm $DIR/a.txt $DIR/sub/c.bin; echo geaendert-laenger > $DIR/b.txt"
restore() {   # restore PASSWORD
    ui tap "ZIP-Sicherung auswählen|Choose ZIP backup" >/dev/null; wait_picker
    E2E_TAP_ANY=1 ui tap "$(basename "$zip")" title >/dev/null; wait_on backup.BackupActivity 6; guard # in the system picker
    sleep 1 # deliberate: the password field takes the focus; the text is typed blind
    a shell input text "$1"; ui tap button1 >/dev/null; guard
}
restore falsch123
check "a wrong password is told apart" ui see "Passwort erneut eingeben|Enter password again" statusButton
check "a wrong password writes nothing" a shell "test ! -e $DIR/a.txt"
st; sleep 2 # deliberate: the password question comes back; the keys are typed blind
a shell "input keyevent 123; for i in \$(seq 12); do input keyevent 67; done"
a shell input text $PW; ui tap button1 >/dev/null
check "the right password shows the preview" ui see "Bereit zum Wiederherstellen|Ready to restore" statusTitle; guard
st
check "the restore is done" ui see "Wiederherstellung fertig|Restore done" statusTitle; guard
check "the restored files are the backed up ones" test "$(sums)" = "$before"
check "the changed file is never replaced" test "$(a shell "cat $DIR/b.txt" | tr -d '\r')" = geaendert-laenger
check "its backed up version comes back beside it" \
    test "$(a shell "md5sum '$DIR/b (restored).txt'" | awk '{print $1}')" = "$b_sum"
st
ui tap Sicherung >/dev/null; wait_on backup.BackupActivity 5; guard
restore $PW
# Without this the next check passed even when the second restore never ran (W90, 20.11.2026).
check "the second restore shows the preview" ui see "Bereit zum Wiederherstellen|Ready to restore" statusTitle
st; ui see "Wiederherstellung fertig|Restore done" statusTitle >/dev/null; guard
check "restoring twice adds no second copy" a shell "test ! -e '$DIR/b (restored 2).txt'"
st

# GB-10: an older backup chosen as the target must stay as it is; Ordo asks for a new file name.
zip_sum=$(a shell "md5sum '$zip'" | awk '{print $1}')
ui tap Sicherung >/dev/null; sleep 2.5; guard
ui checked ordo-e2e-restore || ui tap ordo-e2e-restore titleText >/dev/null
ui tap sdRow >/dev/null; sleep 0.5
ui tap startButton >/dev/null; sleep 3; picker
ui tap "$(basename "$zip")" title >/dev/null; sleep 2
ui tap button1 >/dev/null; sleep 6   # the picker's "replace this file?" question
check "an old backup as target is refused with a hint" \
    ui has "neuen Dateinamen|new file name" statusMessage
check "the old backup ZIP is left as it was" \
    test "$(a shell "md5sum '$zip'" | awk '{print $1}')" = "$zip_sum"
st; sleep 2

# The ticks as they were; the test folder goes, so its entry leaves the list by itself.
ui tap Sicherung >/dev/null; guard
ui tap ordo-e2e-restore titleText >/dev/null
for t in "${ticked[@]}"; do ui tap "$t" titleText >/dev/null; done
all_ticked() { local t; for t in "${ticked[@]}"; do ui checked "$t" || return 1; done; }
check "the ticks are back as they were" all_ticked
back 2
a shell "rm -rf $DIR; rm -f '$zip'"
finish
