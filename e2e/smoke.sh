#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Start, home screen, every settings section, back paths. Read-only.
. "$(dirname "$0")/lib.sh" "$@"
launch
check "home shows categories" scroll_to "Audio"
# On a cold start with a big storage the card can take ~5 s on a tablet: wait for it instead of racing it.
# With a large display size (Galaxy S21 Ultra at 600 dpi) it sits below the fold: scroll on to it.
storage_card() { scroll_to analyzeButton 6 scrollView; }
check "home shows a storage card" storage_card
a shell am start -n "$PKG/me.zhanghai.android.files.settings.SettingsActivity" >/dev/null; sleep 3; guard
for section in "Aussehen|Appearance" "KI-Assistent|AI assistant" "Dateien & Ansicht|Files & view" \
    "Speicher & Ordner|Storage & folders" "Papierkorb|Trash"; do
    # A landscape phone shows only the first rows; the later sections are on the same page, further down.
    if scroll_to "$section" && ui tap "$section" >/dev/null 2>&1; then
        sleep 1; guard
        check "settings: $section opens" on settings.SettingsActivity
        back
        # Back keeps the overview where it was, so the first row can sit above the window.
        for _ in 1 2 3 4 5 6; do ui has "Aussehen|Appearance" && break; swipe_down; done
        check "settings: back from $section shows the overview" ui has "Aussehen|Appearance"
    else
        fail "settings: $section not found"
    fi
done
back
check "back from settings leaves it" sh -c '! [ "$1" = "$2" ]' _ "$(top)" "$PKG/me.zhanghai.android.files.settings.SettingsActivity"
finish
