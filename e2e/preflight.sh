#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Checks in a few seconds that a device run can start at all, so no run fails hours later on a broken setup
# (21.11.2026: a copy without app/src/test/resources, a suite without its execute bit, a notification question on a
# fresh install).
#   e2e/preflight.sh <adb serial>                  host, checkout and device
#   e2e/preflight.sh --install <apk> <adb serial>  first installs the APK, only if aapt says it is Ordo Dev
# Prints one line per check and exits 1 when anything is missing. It changes nothing on the device, except: with
# E2E_GRANT=1 (a test device) it grants notifications and all-files access, and it turns a raised media volume down.
# PKG as in lib.sh; the expected versionCode is app/build.gradle's (E2E_VERSION_CODE overrides; "any" skips it).
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
apk=""; [ "${1:-}" = "--install" ] && { apk=${2:?--install needs an APK}; shift 2; }
SERIAL=${1:?usage: e2e/preflight.sh [--install <apk>] <adb serial>}
ADB=${ADB:-$(command -v adb || echo "$HOME/Android/Sdk/platform-tools/adb")}
PKG=${PKG:-com.beko2210.ordo}
DEV=com.beko2210.ordo.dev
MIN_FREE_MB=${E2E_MIN_FREE_MB:-2048}
bad=0; started=$(date +%s)
ok() { echo "ok   $1"; }
no() { echo "FAIL $1"; bad=$((bad + 1)); }
a() { "$ADB" -s "$SERIAL" "$@" < /dev/null; }

# --- the checkout: everything a suite reads besides e2e/ itself
res=$HERE/../app/src/test/resources
missing=$(grep -oh 'app/src/test/resources/[A-Za-z0-9_./-]*[A-Za-z0-9]' "$HERE"/*.sh | sort -u |
    while read -r f; do [ -e "$HERE/../$f" ] || echo "$f"; done)
if [ -d "$res/ai" ] && [ -d "$res/tags" ] && [ -z "$missing" ]; then ok "test resources (app/src/test/resources)"
else no "test resources missing: ${missing:-app/src/test/resources/ai, tags} (copy the whole checkout, not only e2e/)"; fi
suites=$(sed -n 's/^tests="\([^"]*\)".*/\1/p; s/.*tests="\$tests \([^"]*\)".*/\1/p' "$HERE/run.sh")
notx=$(for t in $suites; do [ -x "$HERE/$t.sh" ] || echo "$t.sh"; done)
[ -z "$notx" ] && ok "all $(echo $suites | wc -w) suites of run.sh are there and executable" ||
    no "suites missing or not executable: $(echo $notx)"

# --- the host's tools
[ -x "$ADB" ] && ok "adb" || no "adb not found (ADB=$ADB)"
python3 -c 'import PIL' 2>/dev/null && ok "python3 with Pillow" || no "python3 with Pillow (pip install pillow)"
if command -v ffmpeg >/dev/null; then
    have=$(ffmpeg -hide_banner -encoders 2>/dev/null | awk '{ print $2 }')
    # every encoder a suite asks ffmpeg for (-c:a / -c:v)
    lack=$(grep -ohE -- '-c:[av] [a-z0-9_]+' "$HERE"/*.sh | awk '{ print $2 }' | sort -u | grep -v '^copy$' |
        while read -r e; do grep -qx "$e" <<<"$have" || echo "$e"; done)
    [ -z "$lack" ] && ok "ffmpeg with every encoder the suites use" || no "ffmpeg lacks the encoders: $(echo $lack)"
else no "ffmpeg not found"; fi

# --- the APK, if one is to be installed: only ever Ordo Dev
if [ -n "$apk" ]; then
    aapt=${AAPT:-$(command -v aapt || ls "$HOME"/Android/Sdk/build-tools/*/aapt 2>/dev/null | tail -1)}
    badge=$("$aapt" dump badging "$apk" 2>/dev/null | head -1)
    name=$(sed -n "s/.*package: name='\([^']*\)'.*/\1/p" <<<"$badge")
    if [ "$name" != "$DEV" ] || [ "$PKG" != "$DEV" ]; then
        no "not installed: $apk is '${name:-unreadable (aapt?)}' and PKG is $PKG; only $DEV is ever installed"
    elif a install -r "$apk" >/dev/null 2>&1; then
        ok "installed $name $(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$badge")"
        E2E_VERSION_CODE=${E2E_VERSION_CODE:-$(sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" <<<"$badge")}
    else no "adb install of $apk failed"; fi
fi

# --- the device: one round trip for everything that is only read
if [ "$(a get-state 2>/dev/null)" != device ]; then
    no "device $SERIAL is not connected (adb devices)"
else
    state=$(a shell "input keyevent KEYCODE_WAKEUP
        echo code=\$(dumpsys package $PKG 2>/dev/null | grep -m1 -o 'versionCode=[0-9]*')
        echo notify=\$(dumpsys package $PKG 2>/dev/null | grep -m1 -o 'POST_NOTIFICATIONS: granted=[a-z]*')
        echo sdk=\$(getprop ro.build.version.sdk)
        echo files=\$(appops get $PKG MANAGE_EXTERNAL_STORAGE 2>/dev/null | head -1)
        echo volume=\$(cmd media_session volume --stream 3 --get 2>/dev/null | grep -o 'volume is [0-9]*')
        echo keyguard=\$(dumpsys window 2>/dev/null | grep -m1 -o 'isKeyguardShowing=[a-z]*')
        echo awake=\$(dumpsys power 2>/dev/null | grep -m1 -o 'mWakefulness=[A-Za-z]*')
        echo free=\$(df -k /sdcard | tail -1 | awk '{ print \$4 }')
        echo override=\$(wm size | grep -c Override)\$(wm density | grep -c Override)" | tr -d '\r')
    get() { sed -n "s/^$1=//p" <<<"$state"; }
    want=${E2E_VERSION_CODE:-$(sed -n "s/.*versionCode .*?: *\([0-9]*\).*/\1/p" "$HERE/../app/build.gradle" 2>/dev/null)}
    code=$(get code | cut -d= -f2)
    if [ -z "$code" ]; then no "$PKG is not installed on the device"
    elif [ "$want" != any ] && [ "$code" != "$want" ]; then no "$PKG has versionCode $code, expected ${want:-?} (E2E_VERSION_CODE)"
    else ok "$PKG versionCode $code"; fi
    grant() { [ "${E2E_GRANT:-}" = 1 ] && a shell "$1" >/dev/null 2>&1; }
    if [ "$(get sdk)" -lt 33 ] 2>/dev/null || [[ $(get notify) == *granted=true ]] ||
        grant "pm grant $PKG android.permission.POST_NOTIFICATIONS"; then ok "notification permission"
    else no "notifications not granted; a test device: E2E_GRANT=1, else: adb -s $SERIAL shell pm grant $PKG android.permission.POST_NOTIFICATIONS"; fi
    if [ "$(get sdk)" -lt 30 ] 2>/dev/null || [[ $(get files) == *": allow"* ]] ||
        grant "appops set $PKG MANAGE_EXTERNAL_STORAGE allow"; then ok "all-files access"
    else no "no all-files access; a test device: E2E_GRANT=1, else: adb -s $SERIAL shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow"; fi
    volume=$(get volume | grep -o '[0-9]*$')
    if [ "$volume" != 0 ]; then # the same way down as lib.sh
        a shell 'for i in $(seq 25); do input keyevent KEYCODE_VOLUME_DOWN; done; cmd media_session volume --stream 3 --set 0' >/dev/null 2>&1
        volume=$(a shell cmd media_session volume --stream 3 --get 2>/dev/null | grep -oE 'volume is [0-9]+' | grep -oE '[0-9]+$')
    fi
    [ "$volume" = 0 ] && ok "media volume 0" || no "media volume is ${volume:-unknown}, not 0"
    [ "$(get keyguard)" = isKeyguardShowing=false ] && [ "$(get awake)" = mWakefulness=Awake ] && ok "unlocked and awake" ||
        no "the device is locked or asleep ($(get keyguard), $(get awake)): unlock it by hand"
    free=$(($(get free) / 1024)) 2>/dev/null || free=0
    [ "$free" -ge "$MIN_FREE_MB" ] && ok "free space $free MB" || no "only $free MB free on /sdcard (need $MIN_FREE_MB)"
    [ "$(get override)" = 00 ] && ok "no screen size or density override" ||
        no "a screen size/density override is set (wm size, wm density): another job uses this device, or one left it"
    # Ordo can come to the front: nothing (a system dialog, a lock) sits on top of it.
    if [ -n "$code" ] && [ "$bad" = 0 ] && [ "${E2E_PREFLIGHT_QUICK:-}" != 1 ]; then
        a shell am start -W -n "$PKG/me.zhanghai.android.files.home.HomeActivity" >/dev/null 2>&1
        top=$(a shell "dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity")
        grep -q " $PKG/" <<<"$top" && ok "Ordo comes to the front" || no "Ordo does not come to the front: $(grep -o 'com[^ }]*' <<<"$top")"
    fi
fi
echo "preflight: $bad problem(s), $(($(date +%s) - started)) s"
[ "$bad" = 0 ]
