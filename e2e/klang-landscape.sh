#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Sound sheet in landscape, WRITES: one stereo WAV in /storage/emulated/0/ordo-e2e-klang-land, removed afterwards;
# turns the screen to landscape and back to what it was. Every card must be reachable: the last one is scrolled to.
# On a window of 840 dp and wider the sheet has two panes (equalizer left, cards right).
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/ordo-e2e-klang-land
OLDAUTO=$(a shell settings get system accelerometer_rotation | tr -d '\r')
OLDROT=$(a shell settings get system user_rotation | tr -d '\r')
tmp=$(mktemp -d)
restore() {
    a shell settings put system user_rotation "${OLDROT:-0}"
    a shell settings put system accelerometer_rotation "${OLDAUTO:-1}"
    a shell "rm -rf $DIR"; rm -rf "$tmp"
}
trap restore EXIT
python3 - "$tmp" <<'PY'
import pathlib, struct, sys, wave
with wave.open(str(pathlib.Path(sys.argv[1]) / "ordo-e2e-land.wav"), "wb") as out:
    out.setnchannels(2); out.setsampwidth(2); out.setframerate(44100)
    out.writeframes(struct.pack("<hh", 1000, -1000) * (44100 * 20))
PY
a shell "rm -rf $DIR; mkdir -p $DIR"; a push "$tmp/ordo-e2e-land.wav" "$DIR/" >/dev/null
wake; grant; a logcat -b crash -c
a shell settings put system accelerometer_rotation 0; a shell settings put system user_rotation 1; sleep 2
a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
    -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
sleep 3; guard
ui tap ordo-e2e-land.wav >/dev/null; sleep 4; guard
a shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1
check "Sound opens in landscape" ui tap actionSound
sleep 2
landscape() { read -r W H < <(screen_size); [ "$W" -gt "$H" ]; }
check "the screen is in landscape" landscape
# In two panes the cards scroll on the right: swipe there (in one column this is inside the sheet too).
swipe_right_pane() {
    read -r W H < <(screen_size)
    a shell input swipe $((W * 3 / 5)) $((H * 3 / 4)) $((W * 3 / 5)) $((H / 4)) 500; sleep 1.2
}
reach() { local i; for i in $(seq 1 25); do ui has "$1" && return 0; swipe_right_pane; done; return 1; }
check "the last card is reachable in landscape" reach "Resume when headphones connect|Beim Verbinden von Kopfhörern fortsetzen"
read -r W H < <(screen_size); DPI=$(a shell wm density | tail -1 | grep -o '[0-9]*$')
if [ $((W * 160 / DPI)) -ge 840 ]; then
    # Two panes: the curve on the left stays in sight while the cards scroll.
    check "the equalizer curve stays visible beside the cards" ui has equalizerCurve
else
    echo "SKIP the equalizer curve stays visible (one column below 840 dp)"
fi
back 1; back 1
a shell am force-stop "$PKG"
finish
