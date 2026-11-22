#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Tresor files never lie on disk as plaintext. Versions before 0.18.0 decrypted an opened image or PDF into
# cache/vault-open; the start of the app now deletes such a leftover, and opening reads through vault.VaultProvider,
# decrypted in memory. The plaintext leftover needs a debug build (run-as). FLAG_SECURE is checked on every build:
# off when the package is debuggable, on otherwise (release and beta). The viewer timeout needs
# `debug.ordo.vault.viewer_timeout_ms`, which release builds ignore.
# Opening a real Tresor image needs the screen-lock prompt: SKIP, by hand (put a demo image in the Tresor, unlock,
# tap it: it shows in Ordo's viewer without Share/Delete, and `run-as $PKG ls -laR cache` lists no copy of it).
. "$(dirname "$0")/lib.sh" "$@"
VIEWER=me.zhanghai.android.files.viewer.image.ImageViewerActivity
PDF=me.zhanghai.android.files.viewer.pdf.PdfViewerActivity
URI="content://$PKG.vault/e2e-secure-check"
leftover() { a shell run-as "$PKG" ls cache/vault-open 2>/dev/null | grep -q .; }
debuggable() { a shell dumpsys package "$PKG" | tr -d '\r' | grep -q 'flags=\[.*DEBUGGABLE'; }
# secure|open|missing. FLAG_SECURE is bit 0x2000 of the window's fl= field (hex, no 0x prefix).
window_flag() {
    a shell dumpsys window | tr -d '\r' | python3 -c '
import sys, re
cls = sys.argv[1]
blocks = re.split(r"(?=Window #|Window\{)", sys.stdin.read())
for block in blocks:
    if cls not in block:
        continue
    match = re.search(r"\bfl=([0-9a-fA-F]+)", block)
    if match:
        print("secure" if int(match.group(1), 16) & 0x2000 else "open")
        raise SystemExit
    if re.search(r"\bmSecure=true\b", block):
        print("secure")
        raise SystemExit
    if re.search(r"\bmSecure=false\b", block):
        print("open")
        raise SystemExit
print("missing")
' "$1"
}
# The activity must be in front. A missing window fails on debug and on release.
check_secure() {
    local screen=$1 class=$2 state
    state=$(window_flag "$class" | tr -d '\r')
    if ! on "$screen"; then
        fail "$class is not in front (window $state)"
        return
    fi
    if [ "$state" = missing ]; then
        fail "$class window has no FLAG_SECURE bit"
        return
    fi
    if debuggable; then
        if [ "$state" = open ]; then pass "$class leaves FLAG_SECURE off on a debug build"
        else fail "$class sets FLAG_SECURE on a debug build"; fi
    else
        if [ "$state" = secure ]; then pass "$class sets FLAG_SECURE outside debug"
        else fail "$class has no FLAG_SECURE outside debug"; fi
    fi
}

wake
a logcat -b crash -c
a shell am force-stop "$PKG"
a shell am start -W -a android.intent.action.VIEW -d "$URI" -t image/jpeg -n "$PKG/$VIEWER" >/dev/null
sleep 2
check_secure viewer.image.ImageViewerActivity "$VIEWER"
a shell am force-stop "$PKG"
a shell am start -W -a android.intent.action.VIEW -d "$URI" -t application/pdf -n "$PKG/$PDF" >/dev/null
sleep 2
check_secure viewer.pdf.PdfViewerActivity "$PDF"
a shell am force-stop "$PKG"

if debuggable; then
    a shell setprop debug.ordo.vault.viewer_timeout_ms 1000
    trap 'a shell setprop debug.ordo.vault.viewer_timeout_ms ""' EXIT
    a shell am start -W -a android.intent.action.VIEW -d "$URI" -t image/jpeg -n "$PKG/$VIEWER" >/dev/null
    sleep 1
    if ! on viewer.image.ImageViewerActivity; then
        fail "timeout setup: the viewer did not open"
    else
        a shell input keyevent KEYCODE_HOME
        sleep 2
        # Same instance. A plain start is a new one and its clock is still zero.
        a shell am start -W -f 0x20000 -a android.intent.action.VIEW -d "$URI" -t image/jpeg -n "$PKG/$VIEWER" >/dev/null
        sleep 2
        if on viewer.image.ImageViewerActivity; then
            fail "a vault image viewer stays open after the debug timeout"
        else
            pass "a vault image viewer closes after the debug timeout"
        fi
    fi
    a shell setprop debug.ordo.vault.viewer_timeout_ms ""
else
    echo "SKIP vault viewer timeout (the override is compiled out of release builds)"
fi

if ! a shell run-as "$PKG" true 2>/dev/null; then
    echo "SKIP no debug build of $PKG (run-as refused) — plaintext leftover not checked"
    finish
fi
a shell am force-stop "$PKG"
a shell run-as "$PKG" sh -c "'mkdir -p cache/vault-open/1-old && echo geheim > cache/vault-open/1-old/a.jpg'"
check "an old plaintext copy is planted" leftover
launch
sleep 2
if leftover; then fail "the start deletes the old plaintext copy in cache/vault-open"; else
    pass "the start deletes the old plaintext copy in cache/vault-open"; fi
check "the vault provider is registered" \
    bash -c "'$ADB' -s '$SERIAL' shell dumpsys package '$PKG' | grep -q '$PKG.vault'"
echo "SKIP opening a Tresor image (screen-lock prompt; check by hand as the header says)"
a shell am force-stop "$PKG"
finish
