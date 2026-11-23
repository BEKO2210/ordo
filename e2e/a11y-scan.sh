#!/bin/bash
# farm: orient=once timeout=600
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Accessibility scan, one pass per screen (home, settings and its sections): reads each screen's uiautomator dump and
# fails for a clickable element with neither text nor contentDescription, or with a touch target under 48 dp.
# Read-only.
. "$(dirname "$0")/lib.sh" "$@"
density=$(a shell wm density | grep -o '[0-9]*' | tail -1)
[ -n "$density" ] || { fail "display density unknown"; finish; }
out=~/.cache/ordo-e2e/a11y-${SERIAL//[^A-Za-z0-9]/_}.xml

# scan NAME: dump the screen in front and report each finding.
scan() {
    local findings
    guard
    rm -f "$out"; mkdir -p "$(dirname "$out")"
    a shell "rm -f /data/local/tmp/ordo-a11y.xml; uiautomator dump /data/local/tmp/ordo-a11y.xml >/dev/null"
    a pull /data/local/tmp/ordo-a11y.xml "$out" >/dev/null 2>&1 || { fail "a11y: $1: no screen dump"; return; }
    findings=$(python3 "$HERE/a11y-scan.py" "$out" "$density")
    if [ -z "$findings" ]; then pass "a11y: $1"; else fail "a11y: $1"; sed 's/^/    /' <<<"$findings"; fi
}

launch
scan "home"
a shell am start -n "$PKG/me.zhanghai.android.files.settings.SettingsActivity" >/dev/null; sleep 3; guard
scan "settings"
for section in "Aussehen|Appearance" "KI-Assistent|AI assistant" "Dateien & Ansicht|Files & view" \
    "Speicher & Ordner|Storage & folders" "Papierkorb|Trash"; do
    if scroll_to "$section" && ui tap "$section" >/dev/null 2>&1; then
        sleep 1; guard
        scan "settings: $section"
        back
        for _ in 1 2 3 4 5 6; do ui has "Aussehen|Appearance" && break; swipe_down; done
    else
        fail "a11y: settings section $section not found"
    fi
done
finish
