#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Start, home screen, every settings section, back paths. Read-only.
. "$(dirname "$0")/lib.sh" "$@"
launch
check "home shows categories" ui has "Audio"
check "home shows a storage card" ui has analyzeButton
a shell am start -n "$PKG/me.zhanghai.android.files.settings.SettingsActivity" >/dev/null; sleep 3; guard
for section in "Aussehen|Appearance" "KI-Assistent|AI assistant" "Dateien & Ansicht|Files & view" \
    "Speicher & Ordner|Storage & folders" "Papierkorb|Trash"; do
    if ui tap "$section" >/dev/null 2>&1; then
        sleep 1; guard
        check "settings: $section opens" on settings.SettingsActivity
        back
        check "settings: back from $section shows the overview" ui has "Aussehen|Appearance"
    else
        fail "settings: $section not found"
    fi
done
back
check "back from settings leaves it" sh -c '! [ "$1" = "$2" ]' _ "$(top)" "$PKG/me.zhanghai.android.files.settings.SettingsActivity"
finish
