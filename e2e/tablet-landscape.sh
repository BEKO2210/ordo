#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Regression (after 0.8.1): on a tablet in landscape Ordo crashed as soon as a file was selected, because the
# selection bar sat in the side-pane layout. Opens Downloads as a landscape tablet, selects a file and opens "More".
# On an emulator it sets 2560x1600 at 320 dpi and restores the exact previous overrides (also on Ctrl-C); a real
# device is never resized or rotated: the test runs only if it already is a tablet in landscape, else it skips.
# Read-only.
. "$(dirname "$0")/lib.sh" "$@"
export E2E_FAST=0
# Current screen in its present rotation, e.g. "2560 1600", and density.
cur_size() { a shell dumpsys window displays | grep -o -m1 'cur=[0-9]*x[0-9]*' | sed 's/cur=//; s/x/ /'; }
cur_dpi() { a shell wm density | tail -1 | sed 's/.*: //' | tr -d '\r'; }
is_emulator() {
    case "$SERIAL" in emulator-*) return 0 ;; esac
    [ "$(a shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || [ "$(a shell getprop ro.boot.qemu | tr -d '\r')" = 1 ]
}
if is_emulator; then
    # Only an emulator is resized, and it gets back exactly the overrides it had (none, or e.g. a capture setup).
    old_size=$(a shell wm size | sed -n 's/^Override size: //p' | tr -d '\r')
    old_dpi=$(a shell wm density | sed -n 's/^Override density: //p' | tr -d '\r')
    restore() {
        if [ -n "$old_size" ]; then a shell wm size "$old_size"; else a shell wm size reset; fi
        if [ -n "$old_dpi" ]; then a shell wm density "$old_dpi"; else a shell wm density reset; fi
    }
    trap restore EXIT
    trap 'exit 130' INT TERM
    a shell wm size 2560x1600; a shell wm density 320; sleep 2
else
    # A real device is never resized or rotated: it runs only when it already is a tablet in landscape.
    read -r w h < <(cur_size); dpi=$(cur_dpi)
    if [ -z "$w" ] || [ "$w" -le "$h" ] || [ $((h * 160 / dpi)) -lt 600 ]; then
        echo "SKIP: not a tablet in landscape (${w}x${h} at ${dpi} dpi); this device is never resized or rotated"
        exit 0
    fi
fi
launch
ui tap "Downloads" >/dev/null; sleep 4
# Checked before guard(): a crash puts the launcher in front, which would only abort the run.
check "a folder opens on a tablet in landscape without a crash" test "$(crashes)" = 0
guard
first=$(ui texts | grep -m1 '|nameText|' | cut -d'|' -f1)
ui longtap "$first" >/dev/null; sleep 1.5; guard
check "selecting a file shows the bar" ui visible selectionMoreButton
read -r bx1 by1 _ _ < <(ui bounds selectionBar | head -1)
read -r _ _ px2 _ < <(ui bounds navigationFragment | head -1)
check "the bar stays clear of the side pane" test "${bx1:-0}" -ge "${px2:-1}"
ui tap selectionMoreButton >/dev/null; sleep 1.2; guard
read -r _ _ _ sy2 < <(ui bounds "Alles auswählen|Select all" | head -1)
check "\"More\" opens its menu above the bar" test "${sy2:-99999}" -le "${by1:-0}"
back; back
finish
