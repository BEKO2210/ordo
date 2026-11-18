#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Casting to a real Chromecast / Google TV, WRITES: creates /storage/emulated/0/ordo-e2e-cast with two quiet WAV files
# and removes it afterwards. Needs a cast device on the same Wi-Fi: CAST_TV=<its name> (SKIP without it).
# Lower the TV's volume beforehand; the test turns it down further with the phone's volume keys.
# SHOTS=<dir> saves screenshots, only while Ordo is in front. Opens the first title, casts it, checks that the
# media session plays remotely, that media Pause and Next control the TV, and that ending casting brings the queue
# back to the phone, paused.
. "$(dirname "$0")/lib.sh" "$@"
TV=${CAST_TV:-}
[ -z "$TV" ] && { echo "SKIP cast: set CAST_TV to the name of a cast device on this Wi-Fi"; exit 0; }
DIR=/storage/emulated/0/ordo-e2e-cast
tmp=$(mktemp -d); trap 'a shell "rm -rf $DIR"; rm -rf "$tmp"' EXIT

python3 - "$tmp" <<'PY'
import math, pathlib, struct, sys, wave
root = pathlib.Path(sys.argv[1])
# 16-bit stereo, quiet tones (about -26 dBFS), 60 s each.
for name, freq in (("01-cast-one.wav", 440.0), ("02-cast-two.wav", 660.0)):
    with wave.open(str(root / name), "wb") as out:
        out.setnchannels(2); out.setsampwidth(2); out.setframerate(22050)
        frame = [int(1600 * math.sin(2 * math.pi * freq * i / 22050)) for i in range(22050)]
        second = b"".join(struct.pack("<hh", s, s) for s in frame)
        out.writeframes(second * 60)
PY
a shell "rm -rf $DIR; mkdir -p $DIR"
a push "$tmp/01-cast-one.wav" "$tmp/02-cast-two.wav" "$DIR/" >/dev/null

wake; grant; a logcat -b crash -c
pause_playback() { a shell input keyevent KEYCODE_MEDIA_PAUSE >/dev/null; }
session() { a shell dumpsys media_session | grep -A${1:-30} "package=$PKG"; }
session_state() { local i; for i in $(seq 40); do session | grep -q "state=$1" && return 0; sleep 0.5; done; return 1; }
# A remote session reports the remote playback type (PlaybackInfo type 2) to the system.
session_remote() { session 80 | grep -qiE "volumeType=(REMOTE|2)\b|PLAYBACK_TYPE_REMOTE"; }
shot() { [ -n "${SHOTS:-}" ] && [[ $(top) == "$PKG"/* ]] && a exec-out screencap -p > "$SHOTS/$1.png"; return 0; }
settled() { local i; for i in $(seq 40); do ui has "$@" && return 0; sleep 0.5; done; return 1; }

a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
    -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
sleep 3; guard
check "the test folder opens" ui wait 01-cast-one.wav 10
ui tap 01-cast-one.wav >/dev/null; sleep 3; guard
pause_playback; sleep 1
check "the player has a cast button" ui tap actionCast
check "the cast sheet offers this phone" settled "This phone|Dieses Handy"
# Without the TV on the network every later check would only measure the phone: stop here.
settled "$TV" >/dev/null 2>&1 || { fail "the cast sheet finds $TV (is it awake and on this Wi-Fi?)"; back 1; back 1; finish; }
pass "the cast sheet finds $TV"
sleep 1; shot cast-sheet
ui tap "$TV" >/dev/null
check "the player shows it is casting" settled "Playing on $TV|Läuft auf $TV"
guard; shot casting
a shell input keyevent KEYCODE_MEDIA_PLAY
check "the TV plays the title" session_state PLAYING
check "the session plays remotely" session_remote
for _ in 1 2 3; do a shell input keyevent KEYCODE_VOLUME_DOWN; sleep 0.3; done
paused_now() { a shell input keyevent KEYCODE_MEDIA_PAUSE; session_state PAUSED; }
check "media Pause pauses the TV" paused_now
a shell input keyevent KEYCODE_MEDIA_NEXT
check "media Next loads the second title on the TV" settled 02-cast-two.wav
a shell input keyevent KEYCODE_MEDIA_PAUSE
check "the TV is paused again" session_state PAUSED
shot before-sheet-again
check "the cast sheet opens again" ui tap actionCast
sleep 1; shot cast-sheet-casting
check "Stop casting ends it" ui tap "Stop casting|Streaming beenden"
sleep 2; back 1
not_casting() { ! ui has "Playing on|Läuft auf"; }
check "the player no longer casts" not_casting
check "the queue is back on the phone with the second title" settled 02-cast-two.wav
not_playing() { ! session | grep -q state=PLAYING; }
check "local playback waits paused" not_playing
shot after-casting
back 1
a shell am force-stop "$PKG"
finish
