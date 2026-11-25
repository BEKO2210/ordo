#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# farm: needs=shots
# Regression (0.3.2): back from a folder opened elsewhere returns to the caller, not up the folder path. Read-only.
. "$(dirname "$0")/lib.sh" "$@"
launch
read -r W H < <(screen_size)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
# icon_visible TARGET: the control is on screen and its icon has a WCAG contrast of 3:1 or more against the bar,
# measured from the screenshot's pixels (most frequent colour = bar, strongest other pixel = icon).
icon_visible() {
    local x1 y1 x2 y2
    read -r x1 y1 x2 y2 < <(ui bounds "$1" | head -1) && [ -n "$y2" ] || return 1
    screenshot "$tmp/bar.png" >/dev/null || return 1
    python3 - "$tmp/bar.png" $(((x1 + x2) / 2)) $(((y1 + y2) / 2)) "$dpi" <<'PY'
import sys
from collections import Counter
from PIL import Image
cx, cy, dpi = (int(v) for v in sys.argv[2:5]); h = 12 * dpi // 160
px = list(Image.open(sys.argv[1]).convert("RGB").crop((cx - h, cy - h, cx + h, cy + h)).getdata())
def lum(c):
    r, g, b = (v / 255 / 12.92 if v <= 10 else ((v / 255 + 0.055) / 1.055) ** 2.4 for v in c)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
bar = lum(Counter(px).most_common(1)[0][0])
sys.exit(0 if max((max(lum(p), bar) + 0.05) / (min(lum(p), bar) + 0.05) for p in px) >= 3 else 1)
PY
}
# Same as smoke: one swipe does not reach the storage card on a landscape phone.
storage_card() { scroll_to analyzeButton 6 scrollView; }
if storage_card && ui tap openButton >/dev/null 2>&1; then
    sleep 3; guard
    check "storage opens in the file list" on filelist.FileListActivity
    # Found 21.11.2026 on the owner's Pixel: on phones the toolbar slid away with the list, and a list without menu,
    # search and sort read as broken. It stays now, and its icons stand out from the bar. Only a phone held sideways
    # (values-land, below 600 dp) still lets it slide away: there it has to come back with the first scroll up.
    dpi=$(a shell wm density | tail -1 | sed 's/.*: //' | tr -d '\r')
    swipe_up
    if [ "$W" -gt "$H" ] && [ $((H * 160 / dpi)) -lt 600 ]; then
        a shell input swipe $((W / 2)) $((H / 2)) $((W / 2)) $((H * 3 / 4)) 500; sleep 1.5
    fi
    for icon in "Navigationsmenü öffnen|Open navigation drawer" action_search action_view_sort "Weitere Optionen|More options"; do
        check "scrolled down, the toolbar still shows ${icon##*|} (contrast >= 3:1)" icon_visible "$icon"
    done
    # The end of the list scrolls clear of the + button, so the last row's menu stays reachable.
    # Flings until the rows on screen stop changing: that is the end, however long the list is.
    rows=""
    for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
        for _ in 1 2 3 4; do a shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 80; done
        sleep 2; now=$(ui texts | grep '|.*nameText|')
        [ "$now" = "$rows" ] && break; rows=$now
    done
    still_muted
    last=$(ui bounds menuButton | awk '$4 > m { m = $4 } END { print m + 0 }')
    check "at the end of the list the last row's menu ends above the + button" \
        test "$last" -gt 0 -a "$last" -le "$(ui bounds sd_main_fab | awk 'NR == 1 { print $2 }')"
    back 2
    check "one back returns to home" on home.HomeActivity
else
    fail "storage card not found"
fi
if scroll_to analyzeButton 8 scrollView && ui tap analyzeButton >/dev/null 2>&1 && wait_on analysis.AnalysisActivity 14; then
    pass "analysis opens"
    guard
    back 2
    check "back from the analysis returns to home" on home.HomeActivity
else
    fail "analysis opens"
fi
finish
