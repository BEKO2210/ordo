# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Shared helpers for Ordo's device tests. Source it: `. "$(dirname "$0")/lib.sh" "$@"`; first argument is the adb serial.
# Every test only acts while Ordo is in front, and only touches files it created itself.
set -uo pipefail
SERIAL=${1:?usage: $0 <adb serial>}
export ADB=${ADB:-$(command -v adb || echo "$HOME/Android/Sdk/platform-tools/adb")}
PKG=${PKG:-com.beko2210.ordo}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
FAILS=0

a() { "$ADB" -s "$SERIAL" "$@" < /dev/null; }
ui() { python3 "$HERE/ui.py" "$SERIAL" "$@" < /dev/null; }
top() { a shell dumpsys activity activities | grep -m1 topResumedActivity | grep -o 'com[^ }]*'; }
crashes() { a logcat -b crash -d | grep -c FATAL; }
back() { a shell input keyevent BACK; sleep "${1:-1.5}"; }

pass() { echo "PASS $1"; }
fail() { echo "FAIL $1"; FAILS=$((FAILS + 1)); }
# check NAME COMMAND...: PASS when the command succeeds.
check() { local name=$1; shift; if "$@" >/dev/null 2>&1; then pass "$name"; else fail "$name"; fi; }

# Stops the test the moment another app is in front: nothing outside Ordo is ever tapped.
guard() {
    case "$(top)" in "$PKG"/*) ;; *) echo "ABORT: $(top) is in front, not Ordo"; exit 2 ;; esac
}
on() { [ "$(top)" = "$PKG/me.zhanghai.android.files.$1" ]; }

wake() { a shell input keyevent KEYCODE_WAKEUP; a shell wm dismiss-keyguard; sleep 1; }
# Fresh start on the home screen with an empty crash log.
launch() {
    wake
    a logcat -b crash -c
    a shell am force-stop "$PKG"
    a shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep "${1:-6}"
    guard
}
screen_size() { a shell wm size | tail -1 | sed 's/.*: //; s/x/ /'; }
swipe_up() { read -r W H < <(screen_size); a shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 3)) 500; sleep 1.5; }
swipe_down() { read -r W H < <(screen_size); a shell input swipe $((W / 2)) $((H / 3)) $((W / 2)) $((H * 3 / 4)) 500; sleep 1.5; }

finish() {
    check "no crash" test "$(crashes)" = 0
    [ "$FAILS" = 0 ] && exit 0
    exit 1
}
