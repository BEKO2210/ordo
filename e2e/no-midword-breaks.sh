#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# The owner's rule: no word ever breaks in the middle ("Absp-ielen", "Zufäll-ig" on the Tab's narrow pane).
# Debug builds only (MidwordWatchProvider logs every visible TextView whose layout breaks inside a word); WRITES one
# silent test song with a cover in Music/OrdoE2E-Midword and removes it again. Walks home, settings, the music
# library's four tabs, a folder page, an album page, the player and its Sound sheet, then fails on any logged break.
# Run it once on a phone and once on a tablet.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/Music/OrdoE2E-Midword
LIBRARY=me.zhanghai.android.files.viewer.audio.MusicLauncherActivity
if ! a shell dumpsys package "$PKG" | grep -q midword_watch; then
    echo "SKIP no-midword-breaks (not a debug build: the watcher is missing)"; exit 0
fi
tmp=$(mktemp -d); trap 'a shell rm -rf $DIR; rm -rf "$tmp"' EXIT
python3 - "$tmp/midword.mp3" <<'PY'
import struct, sys, zlib
def png(rgb, n=64):
    rows = b"".join(b"\0" + bytes(rgb) * n for _ in range(n))
    chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", n, n, 8, 2, 0, 0, 0)) + \
        chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")
def frame(name, data):
    return name.encode() + struct.pack(">I", len(data)) + b"\0\0" + data
text = lambda s: b"\0" + s.encode("latin-1")
body = frame("TIT2", text("Ordoe2e Midword Lied")) + frame("TPE1", text("Ordoe2e Ensemble")) + \
    frame("TALB", text("Ordoe2e Midword Album")) + frame("APIC", b"\0image/png\0\x03\0" + png((40, 90, 200)))
size = len(body)
tag = b"ID3\x03\0\0" + bytes([(size >> 21) & 127, (size >> 14) & 127, (size >> 7) & 127, size & 127]) + body
open(sys.argv[1], "wb").write(tag + (b"\xff\xfb\x90\x00" + bytes(413)) * (60 * 44100 // 1152))
PY
a shell "mkdir -p $DIR"; a push "$tmp/midword.mp3" "$DIR/midword.mp3" >/dev/null
a shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$DIR/midword.mp3" >/dev/null 2>&1
sleep 3
a logcat -c
launch
swipe_up; swipe_down
a shell am start -n "$PKG/me.zhanghai.android.files.settings.SettingsActivity" >/dev/null; sleep 3; guard
ui tap "Aussehen|Appearance" >/dev/null && sleep 2 && back
back
a shell am start -W -n "$PKG/$LIBRARY" >/dev/null; sleep 3; guard
for t in "Titel|Songs" "Alben|Albums" "Interpreten|Artists" "Ordner|Folders"; do ui tap "$t" >/dev/null; sleep 2; done
check "a folder page opens" eval 'ui tap OrdoE2E-Midword title || { swipe_up; ui tap OrdoE2E-Midword title; }'
sleep 2; back
ui tap "Alben|Albums" >/dev/null; sleep 2
check "an album page opens" eval 'ui tap "Ordoe2e Midword Album" || { swipe_up; ui tap "Ordoe2e Midword Album"; }'
sleep 2
check "the player opens" eval 'ui tap "Ordoe2e Midword Lied" title && wait_on viewer.audio.AudioPlayerActivity 8'
sleep 2; ui tap playPause >/dev/null; sleep 1
check "the Sound sheet opens" ui tap actionSound
sleep 2; swipe_up; swipe_up; sleep 1
back; back; sleep 2
hits=$(a logcat -d -s OrdoMidword:W | grep MIDWORD)
[ -n "$hits" ] && echo "$hits" | sed 's/.*MIDWORD/  MIDWORD/'
check "no visible text breaks inside a word" test -z "$hits"
a shell am force-stop "$PKG"
finish
