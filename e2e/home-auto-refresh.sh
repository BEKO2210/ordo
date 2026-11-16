#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# The home screen updates itself when files change, but a burst of 50 new files reloads it once or twice, not 50
# times, and nothing reloads while it is in the background. WRITES: 50 tiny files in Download/ordo-e2e-refresh,
# removed at the end.
. "$(dirname "$0")/lib.sh" "$@"
# Reloads are counted in the log, which only debug builds write.
a shell dumpsys package "$PKG" | grep -q "flags=\[.*DEBUGGABLE" || { echo "SKIP: needs a debug build"; exit 0; }
DIR=/sdcard/Download/ordo-e2e-refresh
count() { a logcat -d -s HomeViewModel:D | grep -c reload; }
launch 10
sleep 5; a logcat -c; sleep 10
check "no reload while nothing changes" test "$(count)" = 0
a logcat -c
a shell "mkdir -p $DIR; for i in \$(seq 50); do echo x > $DIR/f\$i.txt; done"; sleep 6; guard
n=$(count); echo "  50 new files: $n reloads"
check "50 new files reload the home screen once or twice" test "$n" -ge 1 -a "$n" -le 2
a shell input keyevent HOME; sleep 2; a logcat -c
a shell "for i in \$(seq 50); do echo y > $DIR/g\$i.txt; done"; sleep 6
check "no reload while the home screen is in the background" test "$(count)" = 0
a shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 5; guard
check "coming back reloads once" test "$(count)" = 1
a shell rm -r "$DIR"
finish
