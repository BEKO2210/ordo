#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Regression (0.3.1, 0.3.3): moving thousands of files to the trash ran out of memory, and the list kept showing
# them. WRITES: creates COUNT small audio files in Music/ordo-e2e-stress, deletes them through Ordo and removes
# exactly its own trash entries afterwards. Nothing else on the device is touched.
. "$(dirname "$0")/lib.sh" "$@"
COUNT=${COUNT:-3000}
DIR=/sdcard/Music/ordo-e2e-stress
a shell "mkdir -p $DIR && cd $DIR && i=0; while [ \$i -lt $COUNT ]; do i=\$((i+1)); head -c 1024 /dev/urandom > zzordoe2e-\$i.mp3; done"
a shell "content call --uri content://media --method scan_volume --arg external_primary" >/dev/null 2>&1
for _ in $(seq 1 30); do
    indexed=$(a shell "content query --uri content://media/external/audio/media --projection _display_name" | grep -c zzordoe2e)
    [ "$indexed" -ge "$COUNT" ] && break; sleep 3
done
check "MediaStore knows all $COUNT files" test "$indexed" -ge "$COUNT"
launch 7
ui tap "Audio" >/dev/null; sleep 6; guard
ui tap action_search >/dev/null; sleep 2; a shell input text zzordoe2e; sleep 6; guard
ui longtap "zzordoe2e-1.mp3" >/dev/null; sleep 1
ui tap selectionMoreButton >/dev/null; ui tap "Alles auswählen|Select all" >/dev/null; sleep 3
selected=$(ui texts | grep -m1 -E 'ausgewählt|selected' | cut -d'|' -f1 | tr -dc 0-9)
check "all $COUNT files are selected (got ${selected:-0})" test "${selected:-0}" = "$COUNT"
ui tap selectionDeleteButton >/dev/null; sleep 1; guard
pid=$(a shell pidof "$PKG"); start=$(date +%s); ui tap button1 >/dev/null
max=0; gone=; empty=
for _ in $(seq 1 90); do
    heap=$(a shell dumpsys meminfo "$PKG" 2>/dev/null | awk '/Java Heap:/{print $3}')
    [ -n "$heap" ] && [ "$heap" -gt "$max" ] && max=$heap
    left=$(a shell "ls $DIR 2>/dev/null | wc -l" | tr -d '\r')
    [ -z "$gone" ] && [ "$left" = 0 ] && gone=$(($(date +%s) - start))
    if [ -n "$gone" ] && ui has "Keine Dateien|No files"; then empty=$(($(date +%s) - start)); break; fi
    sleep 1
done
echo "files gone after ${gone:-?} s, list empty after ${empty:-?} s, peak Java heap $((max / 1024)) MB"
check "all files left the folder within 60 s" test -n "$gone" -a "${gone:-999}" -le 60
check "the list is empty within 15 s after that" test -n "$empty" -a "$(( ${empty:-999} - ${gone:-0} ))" -le 15
check "peak Java heap below 200 MB" test "$max" -lt 204800
check "same process as before" test "$pid" = "$(a shell pidof "$PKG")"
# Clean up: only this test's entries leave the trash.
check "the trash holds the $COUNT test files" test "$(trash_count Music/ordo-e2e-stress)" -ge "$COUNT"
trash_drop Music/ordo-e2e-stress; a shell "rmdir $DIR 2>/dev/null"
check "no test files left in the trash" test "$(trash_count Music/ordo-e2e-stress)" = 0
back; back; back
finish
