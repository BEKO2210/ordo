#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Tresor image and PDF stay out of screenshots and Recents (blocker before 1.0.0), WRITES: creates
# /storage/emulated/0/ordo-e2e-vault with a test PNG and a test PDF (made by Python on the host, a bright red page),
# moves both into the Tresor through the file list's "Move to vault", opens each from the Tresor, then restores both
# out of the Tresor and removes the folder.
# Each viewer window (viewer.image.ImageViewerActivity, viewer.pdf.PdfViewerActivity) must carry FLAG_SECURE whenever
# the Tresor screen itself does (`dumpsys window windows`, the window's fl= line): a release build sets it on the
# Tresor screen, a debug build leaves it off on purpose (VaultActivity), so a debug build only reports SKIP. As a stand-in
# for the Recents preview (the system takes it the same way and leaves secure windows out) a screen capture of the open
# viewer must show no picture: at most 2 % of the pixels in the middle of the screen may be bright. Regression: only VaultActivity and the video
# player set FLAG_SECURE; a Tresor image or PDF showed in Recents and in screenshots.
# Unlocking the Tresor needs the owner's fingerprint or screen lock: the test never types or taps in that prompt, so it
# only runs with E2E_VAULT_HAND=1 and waits up to 90 s for the owner to unlock by hand. Needs a screen lock.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/ordo-e2e-vault
IMG=ordo-e2e-tresor.png
PDF=ordo-e2e-tresor.pdf
[ "${E2E_VAULT_HAND:-}" = 1 ] || { echo "SKIP vault secure flag (unlocking needs the owner: run with E2E_VAULT_HAND=1)"; exit 0; }
tmp=$(mktemp -d)
trap 'a shell "rm -rf $DIR"; rm -rf "$tmp"' EXIT

python3 - "$tmp/$IMG" "$tmp/$PDF" <<'PY'
import struct, sys, zlib
# A 600x600 bright red PNG.
w = h = 600
raw = b"".join(b"\x00" + b"\xff\x20\x20" * w for _ in range(h))
def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) \
    + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
open(sys.argv[1], "wb").write(png)
# A one-page PDF filled red.
content = b"1 0.12 0.12 rg 0 0 612 792 re f"
objects = [b"<< /Type /Catalog /Pages 2 0 R >>", b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
           b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << >> >>",
           b"<< /Length %d >>\nstream\n" % len(content) + content + b"\nendstream"]
out, offsets = b"%PDF-1.4\n", []
for i, body in enumerate(objects, 1):
    offsets.append(len(out)); out += b"%d 0 obj\n" % i + body + b"\nendobj\n"
xref = len(out)
out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1) + b"".join(b"%010d 00000 n \n" % o for o in offsets)
out += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objects) + 1, xref)
open(sys.argv[2], "wb").write(out)
PY
a shell "rm -rf $DIR; mkdir -p $DIR"
a push "$tmp/$IMG" "$tmp/$PDF" "$DIR/" >/dev/null

# secure ACTIVITY: the window of that Ordo activity has FLAG_SECURE (dumpsys prints it as SECURE on its fl= line).
secure() {
    a shell dumpsys window windows | awk -v w="$PKG/me.zhanghai.android.files.$1" '
        /^ *Window #[0-9]+ / { inside = index($0, w) > 0 } inside && /fl=/ { print }' | grep -qw SECURE
}
# blank: a capture of the screen shows (almost) nothing bright, as a secure window gives it.
blank() {
    raw_screenshot "$tmp/shot.raw" || return 1
    python3 - "$tmp/shot.raw" <<'PY'
import sys
data = open(sys.argv[1], "rb").read()
# screencap's raw format: width, height, format (and on newer versions a colour space), then RGBA pixels.
import struct
w, h = struct.unpack("<II", data[:8])
pixels = data[len(data) - w * h * 4:]
# The middle of the screen only: status and navigation bars are not the app's window and may well be bright.
bright = total = 0
for y in range(h // 5, h * 4 // 5, 7):
    row = y * w * 4
    for x in range(w // 10, w * 9 // 10, 7):
        i = row + x * 4
        total += 1
        bright += max(pixels[i], pixels[i + 1], pixels[i + 2]) > 60
print(f"INFO bright pixels: {bright * 100 // max(total, 1)} %")
sys.exit(0 if bright * 50 <= total else 1)
PY
    local ok=$?; rm -f "$tmp/shot.raw"; return $ok
}
open_folder() {
    a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
        -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
    sleep 3; guard; ui wait "$1" 10 >/dev/null
}

launch
open_folder $IMG
ui longtap $IMG >/dev/null; sleep 1; guard
ui tap $PDF >/dev/null; sleep 1; guard
ui tap selectionMoreButton >/dev/null; sleep 1.2; guard
scroll_to "In den Tresor|Move to vault" 3 >/dev/null
ui tap "In den Tresor|Move to vault" >/dev/null; sleep 1.5; guard
ui tap "Verschieben|Move" >/dev/null; sleep 4; guard
check "both test files went into the Tresor" a shell "test ! -e $DIR/$IMG -a ! -e $DIR/$PDF"

launch
scroll_to vaultCard 4 >/dev/null
ui tap vaultCard >/dev/null; sleep 2
check "the Tresor screen opens" wait_on vault.VaultActivity 6
if ui has "Richte zuerst eine Bildschirmsperre ein|Set up a screen lock first"; then
    echo "SKIP no screen lock on this device: the Tresor cannot be unlocked"
    a shell am force-stop "$PKG"; finish
fi
echo "INFO unlock the Tresor by hand now (fingerprint or screen lock); waiting up to 90 s"
if ! ui wait $IMG 90 >/dev/null; then
    fail "the Tresor was unlocked and lists the test image"
    a shell am force-stop "$PKG"; finish
fi
guard
vault_secure=0; secure vault.VaultActivity && vault_secure=1
echo "INFO the Tresor screen itself has FLAG_SECURE: $vault_secure"

# view NAME ACTIVITY: opens a Tresor file and checks its viewer's window.
view() {
    guard; ui tap "$1" >/dev/null
    if ! wait_on "$2" 8; then fail "$1 opens in ${2##*.}"; return; fi
    sleep 2; guard
    if [ "$vault_secure" = 1 ]; then
        check "$1: the viewer window has FLAG_SECURE, like the Tresor screen" secure "$2"
        check "$1: a screen capture (as the Recents preview) shows no picture" blank
    else
        echo "SKIP $1: this build leaves FLAG_SECURE off on the Tresor screen too (debug build): run on a release build"
    fi
    back 1.5
    wait_on vault.VaultActivity 4 || back 1.5
}
view $IMG viewer.image.ImageViewerActivity
view $PDF viewer.pdf.PdfViewerActivity

# Both back out of the Tresor (to the test folder they came from), then the folder goes.
guard
if on vault.VaultActivity && ui has $IMG; then
    ui longtap $IMG >/dev/null; sleep 1; guard
    ui tap $PDF >/dev/null; sleep 1; guard
    ui tap restoreButton >/dev/null; sleep 4; guard
fi
check "the test files left the Tresor again" a shell "test -e $DIR/$IMG -a -e $DIR/$PDF"
back 1
a shell am force-stop "$PKG"
finish
