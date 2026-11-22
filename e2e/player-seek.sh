#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# The player's wavy seek bar and the house rule against sliders that move by accident, WRITES: one 3-minute silent
# WAV in /storage/emulated/0/ordo-e2e-seek, removed afterwards. A tap, a quick swipe and a vertical scroll over the
# bar never move the position; a finger held 400 ms and then dragged to three quarters does; a double tap on the
# cover's right half jumps 10 s ahead. Positions come from `dumpsys media_session`, with the song paused.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/ordo-e2e-seek
tmp=$(mktemp -d); trap 'a shell "rm -rf $DIR"; rm -rf "$tmp"' EXIT
python3 - "$tmp/seek.wav" <<'PY'
import sys, wave
with wave.open(sys.argv[1], "wb") as out:
    out.setnchannels(1); out.setsampwidth(1); out.setframerate(8000)
    out.writeframes(bytes([128]) * 8000 * 180)
PY
a shell "rm -rf $DIR; mkdir -p $DIR"; a push "$tmp/seek.wav" "$DIR/" >/dev/null
wake; grant; a logcat -b crash -c
pause() { media_key PAUSE 1.5; }
pos() { a shell dumpsys media_session | grep -A25 "package=$PKG" | grep -m1 "position=" | grep -o "position=[0-9]*" \
    | head -1 | cut -d= -f2; }
a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
    -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
sleep 3; guard
pause; ui tap seek.wav >/dev/null; sleep 3; guard; pause
check "audio opens Ordo's player" on viewer.audio.AudioPlayerActivity
# MDC's slider hides its own node from uiautomator (only its virtual thumb is exposed): it lies between the artist
# line (or the lyrics and up-next pills under it) and the times, as wide as the times row.
top=$(ui bounds "artist|lyricsChip|queueChip" | awk '$4 > m { m = $4 } END { print m }')
read -r x1 bottom _ _ < <(ui bounds elapsed | head -1)
read -r _ _ x2 _ < <(ui bounds remaining | head -1)
y=$(((top + bottom) / 2)); w=$((x2 - x1)); xa=$((x1 + w / 4)); xb=$((x1 + w * 3 / 4))
moved_less() { [ $(($2 - $1)) -lt 3000 ] && [ $(($1 - $2)) -lt 3000 ]; }
p0=$(pos); a shell input tap $xb $y; sleep 1.5; p1=$(pos) # deliberate: the gesture has to take effect before the value is read (no event to wait for)
check "a tap on the bar does not seek ($p0 -> $p1 ms)" moved_less "$p0" "$p1"
a shell input swipe $xa $y $xb $y 120; sleep 1.5; p2=$(pos)
check "a quick swipe along the bar does not seek ($p1 -> $p2 ms)" moved_less "$p1" "$p2"
a shell input swipe $xb $((y - 300)) $xb $((y + 300)) 200; sleep 1.5; p3=$(pos)
check "a vertical scroll over the bar does not seek ($p2 -> $p3 ms)" moved_less "$p2" "$p3"
a shell "input motionevent DOWN $xa $y; sleep 0.4; input motionevent MOVE $((xa + w / 8)) $y; \
    input motionevent MOVE $((xa + w / 4)) $y; input motionevent MOVE $xb $y; sleep 0.3; input motionevent UP $xb $y"
sleep 1.5; p4=$(pos) # deliberate: the gesture has to take effect before the value is read (no event to wait for)
# Three quarters of 180 s is 135 s; the slider's own track inset leaves a few seconds either way.
check "held 400 ms and dragged, the bar seeks to three quarters ($p4 ms)" test "$p4" -gt 120000 -a "$p4" -lt 150000
read -r c1 d1 c2 d2 < <(ui bounds artworkCard | head -1)
cx=$((c1 + (c2 - c1) * 3 / 4)); cy=$(((d1 + d2) / 2))
# A pause between the taps: on a fast phone two back-to-back taps come under GestureDetector's 40 ms minimum gap.
a shell "input tap $cx $cy; sleep 0.12; input tap $cx $cy"; sleep 1.5; p5=$(pos)
check "a double tap on the cover's right half jumps 10 s ahead ($p4 -> $p5 ms)" \
    test $((p5 - p4)) -ge 8000 -a $((p5 - p4)) -le 12000
pause; back
finish
