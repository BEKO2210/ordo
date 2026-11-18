#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# The home screen updates itself when files change, but a burst of 50 new files reloads it once or twice, not 50
# times, and nothing reloads while it is in the background. WRITES: 50 tiny files in Download/ordo-e2e-refresh,
# removed at the end.
. "$(dirname "$0")/lib.sh" "$@"
# Reloads are counted in the log, which only debug builds write.
a shell dumpsys package "$PKG" | grep -q "flags=\[.*DEBUGGABLE" || { echo "SKIP: needs a debug build"; exit 0; }
DIR=/sdcard/Download/ordo-e2e-refresh
count() { a logcat -d -s HomeViewModel:D | grep -c "reload$"; }
launch 10
sleep 5; a logcat -c; sleep 10
check "no reload while nothing changes" test "$(count)" = 0
a logcat -c
# The storage bar is recorded meanwhile: an automatic reload moves it in place, it never empties and grows in again.
read -r W H < <(screen_size); bar=$(ui bounds usageBar | head -1)
[ "$(echo "$bar" | cut -d' ' -f4)" -gt $((H - 200)) ] 2>/dev/null && { swipe_up; bar=$(ui bounds usageBar | head -1); a logcat -c; }
if ! command -v ffmpeg >/dev/null; then echo "SKIP storage bar check: ffmpeg missing"
elif [ -z "$bar" ]; then echo "SKIP storage bar check: no storage bar on the home screen"; fi
command -v ffmpeg >/dev/null && [ -n "$bar" ] && record 8 /sdcard/ordo-e2e-auto.mp4 & rec=$!
sleep 1
a shell "mkdir -p $DIR; for i in \$(seq 50); do echo x > $DIR/f\$i.txt; done"; sleep 6; guard
n=$(count); echo "  50 new files: $n reloads"
check "50 new files reload the home screen once or twice" test "$n" -ge 1 -a "$n" -le 2
wait $rec
if command -v ffmpeg >/dev/null && [ -n "$bar" ]; then
    tmp=$(mktemp -d); read -r x1 y1 x2 y2 <<< "$bar"; w=$(((x2 - x1) / 2 * 2)); h=$(((y2 - y1) / 2 * 2))
    "$ADB" -s "$SERIAL" pull /sdcard/ordo-e2e-auto.mp4 "$tmp/a.mp4" >/dev/null 2>&1; a shell rm -f /sdcard/ordo-e2e-auto.mp4
    ffmpeg -loglevel error -y -i "$tmp/a.mp4" -vf "fps=15,scale=$W:$H,crop=$w:2:$x1:$((y1 + h / 2))" -f rawvideo -pix_fmt rgb24 "$tmp/a.rgb" < /dev/null
    # Fill = columns in the accent colour (as in bars-replay.sh); the lowest of all frames against the last one.
    low=$(python3 - "$tmp/a.rgb" "$w" <<'PY'
import sys
data = open(sys.argv[1], "rb").read(); w = int(sys.argv[2]); size = w * 2 * 3
fills = [sum(1 for p in range(i * size, i * size + w * 3, 3) if max(data[p:p + 3]) - min(data[p:p + 3]) > 60)
         for i in range(len(data) // size)]
print(100 * min(fills) // max(fills[-1], 1) if fills and fills[-1] > w * 0.03 else "skip")
PY
)
    rm -rf "$tmp"; [ "$low" = skip ] || echo "  storage bar during the reload: lowest $low % of its final length"
    if [ "$low" = skip ]; then echo "SKIP storage bar check: bar nearly empty or not in the accent colour (storage 90 % full or more)"
    else check "the storage bar does not empty and grow in again on an automatic reload" test "$low" -ge 90; fi
fi
a shell input keyevent HOME; sleep 2; a logcat -c
a shell "for i in \$(seq 50); do echo y > $DIR/g\$i.txt; done"; sleep 6
check "no reload while the home screen is in the background" test "$(count)" = 0
a shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n "$PKG/me.zhanghai.android.files.home.HomeActivity" >/dev/null 2>&1; sleep 5; guard
check "coming back reloads once" test "$(count)" = 1
a shell rm -r "$DIR"
finish
