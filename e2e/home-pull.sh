#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Pull to refresh on the home screen only counts when it is meant: a short pull or scrolling back up into the top
# never reloads, a long pull from the top does (once). Reloads are counted in the log ("HomeViewModel: reload").
# Read-only.
. "$(dirname "$0")/lib.sh" "$@"
# Reloads are counted in the log, which only debug builds write.
a shell dumpsys package "$PKG" | grep -q "flags=\[.*DEBUGGABLE" || { echo "SKIP: needs a debug build"; exit 0; }
launch 10
read -r W H < <(screen_size)
DPI=$(a shell wm density | tail -1 | sed 's/.*: //' | tr -d '\r')
px() { echo $(($1 * DPI / 160)); }
X=$((W / 2)); Y=$((H / 4))
# idle: waits (up to 60 s) until no reload runs. While one runs the pull pictogram may still spin, and a pull then
# does nothing: on the Galaxy Tab S9 Ultra one reload of a large library takes ~15 s, and to_top's own long pull
# from the top starts one.
idle() {
    local i last; sleep 2
    for i in $(seq 60); do
        last=$(a logcat -d -s HomeViewModel:D | grep -E "reload( done)?$" | tail -1)
        case "$last" in *"reload done") return ;; *reload) sleep 1 ;; *) return ;; esac
    done
}
# reloads_after CMD...: once idle, runs it, then prints how many reloads followed.
reloads_after() { idle; a logcat -c; "$@"; sleep 5; guard; a logcat -d -s HomeViewModel:D | grep -c "reload$"; }
# pull DP: one finger pull of DP from the top of the page.
pull() { a shell input swipe $X $Y $X $((Y + $(px "$1"))) 600; }
to_top() { swipe_down; swipe_down; }
to_top
check "a 90 dp pull does not refresh" test "$(reloads_after pull 90)" = 0
n=$(reloads_after pull 170); echo "  170 dp pull: $n reloads"
check "a 170 dp pull refreshes once" test "$n" = 1
sleep 3
# Scrolled down, one long swipe that reaches the top and keeps going: before, the rest of it refreshed.
idle
top_y() { ui bounds "recentTitle|analyzeButton|quickAccessTitle" | head -1 | cut -d' ' -f2; }   # page content, not the bar
before=$(top_y)
a shell input swipe $X $((H * 3 / 4)) $X $((H / 4)) 300; sleep 1
# A page that fits the screen (Galaxy Tab S9 Ultra upright, 2960 px) does not scroll: the long swipe then starts at the
# top and is a real pull, which refreshes as it should. The case is only testable where the page scrolled.
if [ "$(top_y)" = "$before" ] && [ -n "$before" ]; then
    echo "SKIP scrolling back up into the top does not refresh (the home page fits the screen, nothing to scroll)"
else
    check "scrolling back up into the top does not refresh" test "$(reloads_after a shell input swipe $X $((H / 5)) $X $((H * 9 / 10)) 900)" = 0
fi
finish
