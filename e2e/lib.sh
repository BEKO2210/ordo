# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Shared helpers for Ordo's device tests. Source it: `. "$(dirname "$0")/lib.sh" "$@"`; first argument is the adb serial.
# Every test only acts while Ordo is in front, and only touches files it created itself.
set -uo pipefail
SERIAL=${1:?usage: $0 <adb serial>}
export ADB=${ADB:-$(command -v adb || echo "$HOME/Android/Sdk/platform-tools/adb")}
export PKG=${PKG:-com.beko2210.ordo} # ui.py reads it for its screenshot guard
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
FAILS=0

# Anything that acts on the screen drops ui.py's remembered screen read, so the next check reads it fresh.
DUMP=~/.cache/ordo-e2e/${SERIAL//[^A-Za-z0-9]/_}.xml
a() { case " $* " in *" input "*|*" am "*|*" monkey "*) rm -f "$DUMP" ;; esac; "$ADB" -s "$SERIAL" "$@" < /dev/null; }
ui() { python3 "$HERE/ui.py" "$SERIAL" "$@" < /dev/null; }
top() { a shell "dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity" | grep -o 'com[^ }]*'; }
crashes() { a logcat -b crash -d | grep -c FATAL; }
back() { a shell input keyevent BACK; sleep "${1:-1.5}"; }

# Privacy (20.11.2026, a phone's home screen went into a screenshot): a screenshot or recording is only taken while
# Ordo is the resumed activity and no keyguard shows. ordo_front checks that; screenshot FILE and raw_screenshot FILE
# are the only ways the tests take one ("SKIP shot (not Ordo)" otherwise, and the file is not written).
ordo_front() {
    local s; s=$(a shell "dumpsys activity activities | grep -m1 topResumedActivity; dumpsys window | grep -m1 isKeyguardShowing; dumpsys window | grep -m1 mCurrentFocus")
    # The focused window too: Samsung's app drawer (opened from the taskbar) covers Ordo while Ordo stays the resumed
    # activity (Tab A8, 20.11.2026: a drawer screenshot passed the check).
    grep -q "topResumedActivity=.* $PKG/" <<<"$s" && ! grep -q "isKeyguardShowing=true" <<<"$s" &&
        grep -q "mCurrentFocus=.* $PKG/" <<<"$s"
}
screenshot() { if ordo_front; then a exec-out screencap -p > "$1"; else echo "SKIP shot (not Ordo): $1"; return 1; fi; }
raw_screenshot() { if ordo_front; then a exec-out screencap > "$1"; else echo "SKIP shot (not Ordo): $1"; return 1; fi; }
pass() { echo "PASS $1"; }
# With E2E_FAILDUMP=<dir>, every failure also leaves the screen it saw there (texts and a screenshot).
fail() {
    echo "FAIL $1"; FAILS=$((FAILS + 1))
    [ -n "${E2E_FAILDUMP:-}" ] || return 0
    mkdir -p "$E2E_FAILDUMP"; local f; f="$E2E_FAILDUMP/$(basename "$0" .sh)-$FAILS"
    ui texts > "$f.txt" 2>/dev/null; ui shot "$f.png" 2>/dev/null; echo "INFO screen at this failure: $f.txt"
}
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
    # The home screen by name: with the "Ordo Musik" icon switched on, a launcher pick (monkey) may open the music app.
    a shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n "$PKG/me.zhanghai.android.files.home.HomeActivity" >/dev/null 2>&1
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
# The current, rotated size: `wm size` gives the natural (portrait) size, so a swipe in landscape went off screen.
screen_size() {
    local cur; cur=$(a shell dumpsys window displays | grep -o 'cur=[0-9]*x[0-9]*' | head -1 | sed 's/cur=//; s/x/ /')
    if [ -n "$cur" ]; then echo "$cur"; else a shell wm size | tail -1 | sed 's/.*: //; s/x/ /'; fi
}
# record S FILE: screen recording of S seconds on the device. A tablet's encoder may refuse its full 2560x1600, and
# screenrecord then falls back to 1280x720, another aspect: above 1920 px it records at half size. Start ffmpeg's
# filter with scale=W:H (screen_size), so crops use the screen's own coordinates on every device.
record() {
    local w h size=""; read -r w h < <(screen_size)
    [ $((w > h ? w : h)) -gt 1920 ] && size="--size $((w / 2))x$((h / 2))"
    ordo_front || { echo "SKIP recording (not Ordo): $2"; return 1; }
    a shell "screenrecord $size --time-limit $1 $2"
}
swipe_up() { read -r W H < <(screen_size); a shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 3)) 500; sleep 1.5; still_muted; }
swipe_down() { read -r W H < <(screen_size); a shell input swipe $((W / 2)) $((H / 3)) $((W / 2)) $((H * 3 / 4)) 500; sleep 1.5; still_muted; }
# A vertical swipe that misses a sheet lands on the video player's volume gesture (Tab went 0 -> 15 at 09:12,
# 20.11.2026): unless volume steps are allowed, mute again at once and fail the run.
still_muted() {
    [ "${E2E_ALLOW_VOLUME:-}" = 1 ] && return 0
    local v; v=$(media_volume); [ "${v:-0}" = 0 ] && return 0
    a shell 'for i in $(seq 25); do input keyevent KEYCODE_VOLUME_DOWN; done'
    fail "a swipe raised the media volume to $v (muted again)"
}

# The wand opens the command sheet. Below the model's RAM floor (CommandModel.MIN_RAM, 10 GB, compared with the
# device's own MemTotal) it opens the setup page that says the AI can't run here: SKIP. Above it, without a model, the setup
# page offers the download: PASS (the sheet opened, as it should). Anything else is FAIL.
check_wand() {
    local name="the wand opens the command sheet" kb
    kb=$(a shell cat /proc/meminfo | awk '/^MemTotal/ { print $2 }')
    if ui has headerOrb; then pass "$name"
    # Enough RAM but no model yet: the sheet opens on its setup page and offers the download. That is right.
    elif [ -n "$kb" ] && [ "$kb" -ge 9765625 ] && ui has setupLayout && ui has setupButton; then
        pass "$name (setup page with the download: no model on this device yet)"
    elif [ -n "$kb" ] && [ "$kb" -lt 9765625 ] && ui has "Auf diesem Gerät läuft die Ordo-KI nicht|This phone can’t run Ordo AI"; then
        echo "SKIP $name (not enough RAM for the model on this device)"
    else fail "$name"; fi
}

# Ordo's trash entries of a test's own files, by the folder they came from (relative to internal storage, e.g.
# Music/ordo-e2e-stress). Ordo records /storage/emulated/0/…; the shell's /sdcard/… never matched, so stress-delete
# left 3000 entries per run behind (15,000 on the Tab S9 Ultra, 14 s per home reload).
trash_pattern() { echo "^path=(/storage/emulated/0|/sdcard)/$1/"; }
trash_count() {
    a shell "grep -lE '$(trash_pattern "$1")' /storage/emulated/0/.OrdoTrash/info/*.info 2>/dev/null | wc -l" | tr -d '\r'
}
trash_drop() {
    a shell "cd /storage/emulated/0/.OrdoTrash/info 2>/dev/null && for f in \$(grep -lE '$(trash_pattern "$1")' *.info); do rm -rf \"../files/\${f%.info}\" \"\$f\"; done"
}

finish() {
    check "no crash" test "$(crashes)" = 0
    [ "$FAILS" = 0 ] && exit 0
    exit 1
}

# scroll_to TARGET [SWIPES]: swipes up until TARGET is on screen (long settings pages and sheets; a landscape tablet
# shows few rows per swipe). Fails when it never shows.
scroll_to() { local i; for i in $(seq 1 "${2:-8}"); do ui has "$1" && return 0; swipe_up; done; ui has "$1"; }

# video_menu ENTRY: the player's ⋮ › ENTRY. While a film plays its controls hide after 4 s, and a screen read during
# playback comes too late to see them (S21, 20.11.2026): paused they stay, so it pauses for the menu and plays on after.
video_menu() {
    local was W H
    was=$(a shell dumpsys media_session | grep -A25 "package=$PKG" | grep -m1 -o 'state=PLAYING')
    [ -n "$was" ] && { a shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1; }
    local try
    for try in 1 2; do # once more when the menu did not open (seen on the Tab after a pause)
        ui has "Weitere Optionen|More options" || { read -r W H < <(screen_size); a shell input tap $((W / 2)) $((H / 2)); sleep 1; }
        ui tap "Weitere Optionen|More options" >/dev/null; sleep 1
        ui wait "$1" 3 >/dev/null && break
        scroll_to "$1" 3 >/dev/null && break # the "More" sheet scrolls on a phone (cards in one column)
        ui has "Weitere Optionen|More options" || back 1 # a menu without the entry closes; never the player
    done
    ui tap "$1" >/dev/null; sleep 1.5
    # A menu still sliding in takes the tap at a stale spot and stays open (Tab, 20.11.2026): once more, read fresh.
    if ui has "$1" title; then rm -f "$DUMP"; ui tap "$1" title >/dev/null; sleep 1.5; fi
    [ -z "$was" ] || a shell input keyevent KEYCODE_MEDIA_PLAY
}

# Night rule (20.11.2026, a test device was heard): every suite starts with media at 0 or does not start at all.
# Volume keys first (on the W90 and the Pixel `cmd media_session volume --set` was unreliable), then --set as a fallback.
media_volume() { a shell cmd media_session volume --stream 3 --get 2>/dev/null | grep -oE 'volume is [0-9]+' | grep -oE '[0-9]+'; }
a shell 'for i in $(seq 25); do input keyevent KEYCODE_VOLUME_DOWN; done'
[ "$(media_volume)" = 0 ] || a shell cmd media_session volume --stream 3 --set 0 >/dev/null 2>&1
[ "$(media_volume)" = 0 ] || { echo "ABORT: media volume is $(media_volume), not 0"; exit 2; }
# ci test, never merged
