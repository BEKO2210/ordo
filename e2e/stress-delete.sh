#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# farm: timeout=1500
# Regression (0.3.1, 0.3.3): moving thousands of files to the trash ran out of memory, and the list kept showing
# them. WRITES: creates COUNT small audio files in Music/ordo-e2e-stress, deletes them through Ordo and removes
# exactly its own trash entries afterwards. Nothing else on the device is touched.
. "$(dirname "$0")/lib.sh" "$@"
COUNT=${COUNT:-3000}
DIR=/sdcard/Music/ordo-e2e-stress
# The same 3000 files of 1 KB random bytes as before, but packed on the host and unpacked in one go: writing them one
# by one in a shell loop on the device took 129 s of the suite (Tab S9, 21.11.2026).
tar=$(mktemp); trap 'rm -f "$tar"' EXIT
python3 - "$tar" "$COUNT" <<'PY'
import io, os, sys, tarfile
with tarfile.open(sys.argv[1], "w") as out:
    for i in range(1, int(sys.argv[2]) + 1):
        info = tarfile.TarInfo(f"zzordoe2e-{i}.mp3"); info.size = 1024
        out.addfile(info, io.BytesIO(os.urandom(1024)))
PY
a push "$tar" /data/local/tmp/ordo-e2e-stress.tar >/dev/null
a shell "mkdir -p $DIR && tar -xf /data/local/tmp/ordo-e2e-stress.tar -C $DIR; rm -f /data/local/tmp/ordo-e2e-stress.tar"
check "all $COUNT files are in the folder" test "$(a shell "ls $DIR | wc -l" | tr -d '\r')" = "$COUNT"
a shell "content call --uri content://media --method scan_volume --arg external_primary" >/dev/null 2>&1
for _ in $(seq 1 30); do
    indexed=$(a shell "content query --uri content://media/external/audio/media --projection _display_name" | grep -c zzordoe2e)
    [ "$indexed" -ge "$COUNT" ] && break; sleep 3
done
check "MediaStore knows all $COUNT files" test "$indexed" -ge "$COUNT"
launch 7
if ! scroll_to "Audio" || ! ui tap "Audio" >/dev/null || ! wait_on filelist.FileListActivity 8; then
    fail "the Audio category opens"; finish
fi
guard
ui tap action_search >/dev/null; sleep 2 # deliberate: the search field takes the focus; the text is typed blind
a shell input text zzordoe2e; ui wait zzordoe2e-1.mp3 10 >/dev/null; guard
# On a landscape phone the keyboard leaves no room for the empty list's text (A15, 25.11.2026): the first back only
# hides it, so back only while it is shown.
a shell dumpsys input_method | grep -q "mInputShown=true" && back
ui longtap "zzordoe2e-1.mp3" >/dev/null
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
