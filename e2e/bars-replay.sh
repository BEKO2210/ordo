#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Regression (0.4.2, 0.4.3): every storage bar grows in again when the home screen comes back, also a bar whose
# numbers did not change (the SD card). Records the screen and measures each bar on its own: at the first frame in
# which the home screen is visible the bar must still be nearly empty, later it must be full. Read-only.
# Needs ffmpeg on the machine that runs the test.
. "$(dirname "$0")/lib.sh" "$@"
command -v ffmpeg >/dev/null || { echo "SKIP bars-replay: ffmpeg missing"; exit 0; }
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
launch 8
ui has usageBar || fail "no storage bar on the home screen"
read -r _ H < <(screen_size)
# Bring the bars into view if they are below the fold, then remember where each one is.
first=$(ui bounds usageBar | head -1 | cut -d' ' -f4)
[ "${first:-0}" -gt $((H - 200)) ] && swipe_up
ui bounds usageBar > "$tmp/bars.txt"
ui tap openButton >/dev/null; sleep 4; guard
check "left the home screen" on filelist.FileListActivity
a shell "screenrecord --time-limit 6 /sdcard/ordo-e2e-bars.mp4" &
sleep 1.5; back 0; wait
"$ADB" -s "$SERIAL" pull /sdcard/ordo-e2e-bars.mp4 "$tmp/bars.mp4" >/dev/null 2>&1; a shell rm -f /sdcard/ordo-e2e-bars.mp4
n=0
while read -r x1 y1 x2 y2; do
    n=$((n + 1)); w=$(((x2 - x1) / 2 * 2)); h=$(((y2 - y1) / 2 * 2))
    ffmpeg -loglevel error -y -i "$tmp/bars.mp4" -vf "fps=15,crop=$w:$h:$x1:$y1" -f rawvideo -pix_fmt rgb24 "$tmp/bar$n.rgb" < /dev/null
    verdict=$(python3 - "$tmp/bar$n.rgb" "$w" "$h" <<'PY'
import sys
path, w, h = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
data = open(path, "rb").read(); size = w * h * 3; frames = len(data) // size
row = (h // 2) * w * 3                      # the middle line of the bar is enough
def line(i): return data[i * size + row:i * size + row + w * 3]
def fill(l): return sum(1 for p in range(0, w * 3, 3) if l[p] < 110 and l[p + 1] > 120 and l[p + 2] > 170)
def track(l): return l[int(w * 0.96) * 3:int(w * 0.99) * 3]     # the far end shows the empty track on the home screen
last = line(frames - 1); final = fill(last)
if final < w * 0.03:
    print("skip: bar is empty or not in the aurora colours (nearly full storage)"); sys.exit()
def visible(l): return max(abs(a - b) for a, b in zip(track(l), track(last))) < 12
shown = next(i for i in range(frames) if visible(line(i)))
start = fill(line(shown)); peak = max(fill(line(i)) for i in range(shown, frames))
ok = start <= 0.35 * final and peak >= 0.9 * final
print(("ok" if ok else "bad") + f": {round(100 * start / final)} % of its length when the home screen appears, {round(100 * peak / final)} % later")
PY
)
    echo "bar $n: $verdict"
    case "$verdict" in ok*|skip*) pass "bar $n grows in after returning" ;; *) fail "bar $n grows in after returning" ;; esac
done < "$tmp/bars.txt"
check "found at least one bar" test "$n" -ge 1
finish
