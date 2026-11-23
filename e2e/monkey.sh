#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# farm: orient=once timeout=2400
# Night robustness (test plan level 8). Only with FARM_NIGHT=1, otherwise SKIP.
# Thirty minutes of adb monkey against $PKG with a seed (MONKEY_SEED, default 53). The run stops at the first
# crash (logcat -b crash) or ANR (logcat -b anr) of this package. Then a process-death loop: Ordo goes to the
# background, `am kill` is started there, the app is opened again, and home still shows its categories.
. "$(dirname "$0")/lib.sh" "$@"
[ "${FARM_NIGHT:-}" = 1 ] || { echo "SKIP monkey (night only: FARM_NIGHT=1)"; exit 0; }

SEED=${MONKEY_SEED:-53}
MINUTES=30
EVENTS=20000 # throttle 200 ms keeps this count past the clock; the clock ends the run
KILLS=5
HOME_ACT=me.zhanghai.android.files.home.HomeActivity
tmp=$(mktemp -d)
mpid=""

quiet() { a shell 'for i in $(seq 25); do input keyevent KEYCODE_VOLUME_DOWN; done' >/dev/null 2>&1; }
# The buffer is read whole first: grep -q on a pipe can take SIGPIPE, and pipefail would hide a real hit.
pkg_in() { local b; b=$(a logcat -b "$1" -d 2>/dev/null | tr -d '\r' || true); grep -F -q "$PKG" <<<"$b"; }
stop_monkey() {
    [ -n "$mpid" ] && kill "$mpid" 2>/dev/null
    local pid; pid=$(a shell pidof com.android.commands.monkey 2>/dev/null | tr -d '\r')
    [ -n "$pid" ] && a shell kill $pid >/dev/null 2>&1
    [ -n "$mpid" ] && wait "$mpid" 2>/dev/null
    mpid=""
}
stop_playback() {
    a shell input keyevent KEYCODE_MEDIA_PAUSE >/dev/null 2>&1
    a shell am stopservice "$PKG/me.zhanghai.android.files.player.AudioPlaybackService" >/dev/null 2>&1
    a shell am stopservice "$PKG/me.zhanghai.android.files.viewer.video.background.VideoPlaybackService" >/dev/null 2>&1
}
open_home() {
    a shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 \
        -n "$PKG/$HOME_ACT" >/dev/null 2>&1
}
trap 'stop_monkey; quiet; rm -rf "$tmp"' EXIT

launch
quiet
a logcat -b crash -c >/dev/null 2>&1
a logcat -b anr -c >/dev/null 2>&1
echo "INFO monkey ${MINUTES} min, seed $SEED, package $PKG"
# System keys stay off, so monkey does not press power or volume. Crashes and ANRs are not ignored.
"$ADB" -s "$SERIAL" shell monkey -p "$PKG" -s "$SEED" --throttle 200 --ignore-security-exceptions \
    --pct-syskeys 0 "$EVENTS" >"$tmp/monkey.log" 2>&1 &
mpid=$!
began=$(date +%s)
deadline=$((SECONDS + MINUTES * 60))
reason=""
while [ "$SECONDS" -lt "$deadline" ]; do
    if pkg_in crash; then reason="crash in logcat -b crash"; break; fi
    if pkg_in anr; then reason="ANR in logcat -b anr"; break; fi
    kill -0 "$mpid" 2>/dev/null || { reason=early; break; }
    sleep 5
done
took=$(($(date +%s) - began))
stop_monkey
quiet
wake
if [ "$reason" = early ] && [ "$took" -ge $((MINUTES * 60 - 15)) ]; then reason=""; fi
if [ -z "$reason" ]; then
    if pkg_in crash; then reason="crash in logcat -b crash"
    elif pkg_in anr; then reason="ANR in logcat -b anr"; fi
fi
[ "$reason" = early ] && reason="monkey ended before ${MINUTES} min"
if [ -n "$reason" ]; then
    fail "monkey stopped after ${took}s: $reason (seed $SEED)"
    echo "INFO last monkey output:"
    tail -20 "$tmp/monkey.log" 2>/dev/null
    finish
fi
pass "monkey ${MINUTES} min, seed $SEED, no crash and no ANR"

# am kill drops a background process only. Home first, then the kill in the background; a playback service
# started by monkey would hold the process, so it is stopped before a second kill.
pid_of() { a shell pidof "$PKG" | awk '{ print $1 }' | tr -d '\r'; }
for i in $(seq 1 "$KILLS"); do
    open_home
    wait_on home.HomeActivity 8 || true
    stop_playback
    a shell input keyevent KEYCODE_HOME
    sleep 1 # deliberate: Ordo is in the background, which is when am kill can drop it
    old=$(pid_of)
    a shell am kill "$PKG" &
    now=$old
    end=$((SECONDS + 5))
    while [ "$SECONDS" -lt "$end" ]; do
        now=$(pid_of)
        { [ -z "$now" ] || [ "$now" != "$old" ]; } && break
        sleep 0.5
    done
    if [ -n "$old" ] && [ "$now" = "$old" ]; then
        stop_playback
        a shell am kill "$PKG" &
        end=$((SECONDS + 5))
        while [ "$SECONDS" -lt "$end" ]; do
            now=$(pid_of)
            { [ -z "$now" ] || [ "$now" != "$old" ]; } && break
            sleep 0.5
        done
    fi
    if [ -n "$old" ] && [ "$now" = "$old" ]; then fail "process death $i: am kill left pid $old"
    else pass "process death $i: the process was killed"; fi
    open_home
    check "process death $i: home is back" wait_on home.HomeActivity 8
    check "process death $i: home shows categories" ui has "Audio"
done
no_anr() { ! pkg_in anr; }
check "no ANR after process death" no_anr
finish
