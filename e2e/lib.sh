# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Shared helpers for Ordo's device tests. Source it: `. "$(dirname "$0")/lib.sh" "$@"`; first argument is the adb serial.
# Every test only acts while Ordo is in front, and only touches files it created itself.
set -uo pipefail
SERIAL=${1:?usage: $0 <adb serial>}
export ADB=${ADB:-$(command -v adb || echo "$HOME/Android/Sdk/platform-tools/adb")}
PKG=${PKG:-com.beko2210.ordo}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
FAILS=0

# Anything that acts on the screen drops ui.py's remembered screen read, so the next check reads it fresh.
DUMP=~/.cache/ordo-e2e/${SERIAL//[^A-Za-z0-9]/_}.xml
a() { case " $* " in *" input "*|*" am "*|*" monkey "*) rm -f "$DUMP" ;; esac; "$ADB" -s "$SERIAL" "$@" < /dev/null; }
ui() { python3 "$HERE/ui.py" "$SERIAL" "$@" < /dev/null; }
top() { a shell "dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity" | grep -o 'com[^ }]*'; }
crashes() { a logcat -b crash -d | grep -c FATAL; }
back() { a shell input keyevent BACK; sleep "${1:-1.5}"; }

pass() { echo "PASS $1"; }
fail() { echo "FAIL $1"; FAILS=$((FAILS + 1)); }
# check NAME COMMAND...: PASS when the command succeeds.
check() { local name=$1; shift; if "$@" >/dev/null 2>&1; then pass "$name"; else fail "$name"; fi; }

# Stops the test the moment another app is in front: nothing outside Ordo is ever tapped.
# A permission question in front names what is missing and the command the owner can run himself.
guard() {
    local t; t=$(top)
    case "$t" in
        "$PKG"/*) return ;;
        *permissioncontroller*) echo "ABORT: $t is in front (Ordo asks for notifications). If you agree, run:" \
            "adb -s $SERIAL shell pm grant $PKG android.permission.POST_NOTIFICATIONS" ;;
        com.android.settings/*) echo "ABORT: $t is in front (all-files access). If you agree, run:" \
            "adb -s $SERIAL shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow" ;;
        *) echo "ABORT: $t is in front, not Ordo" ;;
    esac
    exit 2
}
# Samsung names the launcher alias that started home (launcher.HomeAlias, HomeGreen, …) instead of HomeActivity.
on() {
    local t; t=$(top)
    [ "$t" = "$PKG/me.zhanghai.android.files.$1" ] ||
        { [ "$1" = home.HomeActivity ] && [[ $t == "$PKG/me.zhanghai.android.files.launcher.Home"* ]]; }
}
# wait_on SCREEN [S]: polls until that screen is in front (up to S seconds, default 4) instead of a fixed sleep.
wait_on() { local end=$((SECONDS + ${2:-4})); until on "$1"; do [ $SECONDS -ge "$end" ] && return 1; sleep 0.3; done; }

wake() { a shell input keyevent KEYCODE_WAKEUP; a shell wm dismiss-keyguard; sleep 1; }
# Only the platform's own property counts: adb also names a real phone forwarded to a local port "emulator-<port>".
is_emulator() {
    [ "$(a shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || [ "$(a shell getprop ro.boot.qemu | tr -d '\r')" = 1 ]
}
# On an emulator only, what adb may grant, so no system dialog comes to the front: notifications (Android 13+) and
# all-files access (an app op since Android 11). A real device keeps its owner's choices; guard() then names what is
# missing. Older platforms refuse quietly; a system dialog itself is never tapped.
# Real phones are left alone unless E2E_GRANT=1 marks them as test devices that may be granted access for the run.
grant() {
    is_emulator || [ "${E2E_GRANT:-}" = 1 ] || return 0
    a shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
    a shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1
}
# Cold start with an empty crash log, without touching the first-run welcome pages.
open_app() {
    wake
    grant
    a logcat -b crash -c
    a shell am force-stop "$PKG"
    a shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep "${1:-6}"
}
# After a fresh install or cleared data Ordo first shows its welcome pages; they are skipped through their own button.
skip_onboarding() {
    on home.OnboardingActivity || return 0
    ui tap "Überspringen|Skip" >/dev/null; sleep 3
    on home.OnboardingActivity && { echo "first run: onboarding did not close"; return 1; }
    echo "first run: onboarding skipped"
}
# Fresh start on the home screen with an empty crash log.
launch() {
    open_app "${1:-6}"
    skip_onboarding
    guard
}
screen_size() { a shell wm size | tail -1 | sed 's/.*: //; s/x/ /'; }
swipe_up() { read -r W H < <(screen_size); a shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 3)) 500; sleep 1.5; }
swipe_down() { read -r W H < <(screen_size); a shell input swipe $((W / 2)) $((H / 3)) $((W / 2)) $((H * 3 / 4)) 500; sleep 1.5; }

# The wand opens the command sheet. Below the model's RAM floor (CommandModel.MIN_RAM, 10 GB, compared with the
# device's own MemTotal) it opens the setup page that says the AI can't run here: SKIP. Anything else is FAIL.
check_wand() {
    local name="the wand opens the command sheet" kb
    kb=$(a shell cat /proc/meminfo | awk '/^MemTotal/ { print $2 }')
    if ui has headerOrb; then pass "$name"
    elif [ -n "$kb" ] && [ "$kb" -lt 9765625 ] && ui has "Auf diesem Gerät läuft die Ordo-KI nicht|This phone can’t run Ordo AI"; then
        echo "SKIP $name (not enough RAM for the model on this device)"
    else fail "$name"; fi
}

finish() {
    check "no crash" test "$(crashes)" = 0
    [ "$FAILS" = 0 ] && exit 0
    exit 1
}
