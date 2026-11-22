#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Ordo as a place in Android's own file picker: the system picker (ACTION_OPEN_DOCUMENT, as a browser upload opens it)
# lists "Ordo" among its places, and Ordo shows the user's bookmarks there. Needs all-files access and a bookmark whose
# folder exists (Ordo's default bookmark is Pictures/Screenshots; without it the place is hidden and the test SKIPs).
# Only reads: it opens the picker, looks, and leaves with Back; it never picks, writes or takes a screenshot.
. "$(dirname "$0")/lib.sh" "$@"
export E2E_FAST=0
AUTHORITY="$PKG.documents"
ROOTS="Stammverzeichnisse anzeigen|Show roots|Navigationsleiste öffnen|Open navigation drawer"

launch
check "the provider is registered for the system picker" \
    bash -c "'$ADB' -s '$SERIAL' shell dumpsys package '$PKG' | grep -q '$AUTHORITY'"
a shell am start -a android.intent.action.OPEN_DOCUMENT -c android.intent.category.OPENABLE -t '*/*' >/dev/null
sleep 3
E2E_TAP_ANY=1 ui tap "$ROOTS" >/dev/null 2>&1; sleep 1.5 # the system picker, opened by this test
if ui has "Ordo|Ordo Dev"; then
    pass "the system picker lists Ordo as a place"
    E2E_TAP_ANY=1 ui tap "Ordo|Ordo Dev" >/dev/null
    check "Ordo shows the bookmarks there" ui see "Screenshots|Bildschirmfotos"
else
    echo "SKIP the system picker lists Ordo (no all-files access or no existing bookmark folder on this device)"
fi
back; back; back
a shell am force-stop "$PKG"
finish
