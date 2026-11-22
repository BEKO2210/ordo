#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# --write: creates /sdcard/ordo-e2e-image with one picture, deletes it through the viewer, drops the trash entry.
# A file:// URI under the app's own data directory must not open the viewer, and a path extra from outside Ordo
# must not replace the opened file.
# SMB delete is by hand: open an image on a share, delete it, the list moves on and the app does not crash.
. "$(dirname "$0")/lib.sh" "$@"
not_on() { ! on "$@"; }
not_has() { ! ui has "$1"; }
gone() { ! a shell "[ -f $DIR/a.png ]"; }
VIEWER=me.zhanghai.android.files.viewer.image.ImageViewerActivity
DIR=/sdcard/ordo-e2e-image
PNG=$(mktemp)

wake
a logcat -b crash -c
grant
a shell am force-stop "$PKG"

# GB-12. Do not guard(): the launcher may be what is left in front.
a shell am start -W -a android.intent.action.VIEW -d "file:///data/data/$PKG/files/x.jpg" -t image/jpeg \
    -n "$PKG/$VIEWER" >/dev/null 2>&1 || true
sleep 2
check "a file:// URI under the app data dir opens nothing" not_on viewer.image.ImageViewerActivity

a shell "rm -rf $DIR; mkdir -p $DIR"
printf '%s' 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==' \
    | base64 -d > "$PNG"
a push "$PNG" "$DIR/a.png" >/dev/null
rm -f "$PNG"
a shell am start -W -a android.intent.action.VIEW -d "file://$DIR/a.png" -t image/png -n "$PKG/$VIEWER" >/dev/null
sleep 2
guard
check "the picture opens" ui wait a.png 8

a shell am force-stop "$PKG"
a shell am start -W -a android.intent.action.VIEW -d "file://$DIR/a.png" -t image/png \
    --es "$PKG.extra.PATH_URI" "file:///data/data/$PKG/files/secret.jpg" -n "$PKG/$VIEWER" >/dev/null
sleep 2
guard
check "a foreign path extra is ignored" ui wait a.png 8
check "the private path is not shown" not_has secret.jpg

ui tap "Weitere Optionen|More options" >/dev/null
sleep 1
ui tap "Löschen|Delete" >/dev/null
sleep 1
ui tap OK >/dev/null
sleep 2
check "the picture is gone from the folder" gone
check "the viewer closes when the list is empty" not_on viewer.image.ImageViewerActivity
trash_drop ordo-e2e-image
a shell "rm -rf $DIR"
finish
