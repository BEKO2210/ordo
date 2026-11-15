#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Regression (0.4.2): the storage bars grow in again when the home screen comes back. Records the screen and
# counts the bar-coloured pixels per frame. Read-only. Needs ffmpeg on the machine that runs the test.
. "$(dirname "$0")/lib.sh" "$@"
command -v ffmpeg >/dev/null || { echo "SKIP bars-replay: ffmpeg missing"; exit 0; }
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
launch 8
swipe_up
if ! ui has usageBar; then swipe_up; fi
ui tap "DCIM|Downloads" >/dev/null 2>&1 || ui tap openButton >/dev/null; sleep 4; guard
a shell "screenrecord --time-limit 6 /sdcard/ordo-e2e-bars.mp4" &
sleep 1.5; back 0; wait
"$ADB" -s "$SERIAL" pull /sdcard/ordo-e2e-bars.mp4 "$tmp/bars.mp4" >/dev/null 2>&1; a shell rm -f /sdcard/ordo-e2e-bars.mp4
ffmpeg -loglevel error -i "$tmp/bars.mp4" -vf "fps=8,scale=240:-2" -f rawvideo -pix_fmt rgb24 "$tmp/frames.rgb"
IFS=x read -r W H < <(ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0:s=x "$tmp/bars.mp4")
# Aurora bar pixels (cyan to blue) per frame; while the bars grow, the count rises from its lowest value.
series=$(python3 - "$tmp/frames.rgb" "$W" "$H" <<'PY'
import sys
path, w, h = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
fw = 240; fh = (h * 240 // w) // 2 * 2
data = open(path, "rb").read(); size = fw * fh * 3
counts = []
for i in range(len(data) // size):
    frame = data[i * size:(i + 1) * size]
    counts.append(sum(1 for p in range(0, size, 3) if frame[p] < 110 and frame[p + 1] > 120 and frame[p + 2] > 170))
print(" ".join(map(str, counts)))
PY
)
echo "bar pixels per frame: $series"
check "bars start low and grow after returning" python3 -c "
import sys
c = list(map(int, sys.argv[1].split()))
tail = c[len(c) // 3:]
sys.exit(0 if len(c) >= 10 and max(tail) - min(tail) >= 20 and tail.index(min(tail)) < tail.index(max(tail)) else 1)" "$series"
finish
