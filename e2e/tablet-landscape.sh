#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Regression (after 0.8.1): on a tablet in landscape Ordo crashed as soon as a file was selected, because the selection bar
# sat in the side-pane layout. Turns the screen into a landscape tablet (2560x1600 at 320 dpi), opens Downloads,
# selects a file and opens "More"; puts screen size and density back as they were. Otherwise read-only.
. "$(dirname "$0")/lib.sh" "$@"
export E2E_FAST=0
restore() { a shell wm size reset; a shell wm density reset; }
trap restore EXIT
a shell wm size 2560x1600; a shell wm density 320; sleep 2
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
