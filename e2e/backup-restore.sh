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
# close_picker: back out of the system picker, and only out of it: never past Ordo (W90 rc1b: further backs reached
# the launcher and the farm took the whole device out). Also the exit trap, so a stopped run leaves no picker open.
close_picker() {
    local _; for _ in 1 2 3 4 5 6 7 8; do
        [[ $(top) == *documentsui/* ]] || return 0
        a shell input keyevent KEYCODE_BACK; sleep 1
        [[ $(top) == "$PKG"/* ]] && return 0
    done
}
trap close_picker EXIT
# pui: ui for the system picker, which this test opened itself (ui.py taps only into Ordo without E2E_TAP_ANY=1).
pui() { E2E_TAP_ANY=1 ui "$@"; }
# The form runs on behind the start bar: on a landscape tablet a row behind it is in the screen read, but a tap there
# hits the bar, and a swipe starting on the bar moves nothing (W90 rc1b, as in backup-self-include.sh). form_to scrolls
# the form until TARGET [INSIDE] stands above the bar.
bar_top() { ui bounds startBar | awk '{ print $2; exit }'; }
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
form_to() { local _; above_bar "$@" && return 0; for _ in 1 2 3 4 5 6 7 8; do form_swipe up || return 1; above_bar "$@" && return 0; done; return 1; }
form_top() { local _; for _ in 1 2 3 4 5 6 7 8; do form_swipe down || return 0; done; }
# form_tap TARGET [INSIDE]: scrolled above the bar, then tapped.
form_tap() { form_to "$@" && ui tap "$@" >/dev/null; }
# The keyboard away (Escape), only while it shows: without it Escape counts as Back and left the screen.
hide_keyboard() { a shell dumpsys input_method | grep -q "mInputShown=true" && a shell input keyevent 111; sleep 0.5; }
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
for t in "${ticked[@]}"; do form_tap "$t" titleText; done
form_top
form_to ordo-e2e-restore titleText; ui checked ordo-e2e-restore || form_tap ordo-e2e-restore titleText
# On a short screen (W90) the protection card starts under the bottom bar: a tap on its visible sliver hit the
# system's Home button. Each row is scrolled above the bar first (form_tap).
form_tap sdRow
form_tap encryptRow
form_tap passwordEdit; a shell input text $PW
form_tap passwordRepeatEdit; a shell input text $PW; hide_keyboard
form_tap startButton; wait_picker
# Turned while the picker is open: the backup screen is made anew behind it and must still start the backup.
rotation=$(a shell settings get system user_rotation | tr -d '\r'); auto=$(a shell settings get system accelerometer_rotation | tr -d '\r')
a shell settings put system accelerometer_rotation 0; a shell settings put system user_rotation 1; sleep 3; picker # deliberate: the rotation, with the picker rebuilt
pui tap button1 >/dev/null; wait_on backup.BackupActivity 8 || close_picker   # the system picker's "save", file name given by Ordo
a shell settings put system user_rotation "${rotation:-0}"; a shell settings put system accelerometer_rotation "${auto:-1}"
sleep 1 # deliberate: the rotation back
check "the backup is done" ui see "Sicherung fertig|Backup done" statusTitle; guard
check "the backup ZIP is on the SD card" a shell "test -s '$zip'"
st   # "done" closes the screen
ui tap Sicherung >/dev/null; wait_on backup.BackupActivity 5; guard

a shell "rm $DIR/a.txt $DIR/sub/c.bin; echo geaendert-laenger > $DIR/b.txt"
restore() {   # restore PASSWORD
    ui tap "ZIP-Sicherung auswählen|Choose ZIP backup" >/dev/null; wait_picker
    # In the system picker; the ZIP scrolled into sight first (a landscape tablet shows few rows).
    scroll_to "$(basename "$zip")" 4 >/dev/null; pui tap "$(basename "$zip")" title >/dev/null
    wait_on backup.BackupActivity 6 || close_picker; guard
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
form_to ordo-e2e-restore titleText; ui checked ordo-e2e-restore || form_tap ordo-e2e-restore titleText
form_tap sdRow; sleep 0.5
form_tap startButton; sleep 3; picker
scroll_to "$(basename "$zip")" 4 >/dev/null; pui tap "$(basename "$zip")" title >/dev/null; sleep 2
pui tap button1 >/dev/null; sleep 6   # the picker's "replace this file?" question
close_picker; guard
check "an old backup as target is refused with a hint" \
    ui has "neuen Dateinamen|new file name" statusMessage
check "the old backup ZIP is left as it was" \
    test "$(a shell "md5sum '$zip'" | awk '{print $1}')" = "$zip_sum"
st; sleep 2

# The ticks as they were; the test folder goes, so its entry leaves the list by itself.
ui tap Sicherung >/dev/null; guard
form_top; form_tap ordo-e2e-restore titleText
for t in "${ticked[@]}"; do form_tap "$t" titleText; done
all_ticked() { local t; for t in "${ticked[@]}"; do ui checked "$t" || return 1; done; }
check "the ticks are back as they were" all_ticked
back 2
a shell "rm -rf $DIR; rm -f '$zip'"
finish
