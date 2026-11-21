#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Detail plus picture on a device: device-shots.sh <serial> <tag> <upscaler label>. Plays the still clips s_sita.mp4
# and s_sintel.mp4 (device-clips.sh) landscape and takes a guarded screenshot (Ordo in front) after the controls hid:
# ~/dpf-out/shot_<tag>_<clip>.png. device-quality.sh compares them with the originals.
. "$(dirname "$0")/../../e2e/lib.sh" "$1"
TAG2=$2; LABEL=$3
DIR=/storage/emulated/0/ordo-dpf
out=$HOME/dpf-out; mkdir -p "$out"
rotation=$(a shell settings get system user_rotation | tr -d '\r'); auto=$(a shell settings get system accelerometer_rotation | tr -d '\r')
trap 'a shell am force-stop $PKG; a shell settings put system user_rotation "${rotation:-0}"; a shell settings put system accelerometer_rotation "${auto:-1}"' EXIT
for c in s_sita.mp4 s_sintel.mp4; do a shell "ls $DIR/$c" >/dev/null 2>&1 || a push "$HOME/dpf-clips/$c" "$DIR/$c" >/dev/null; done
wake; grant
a shell settings put system accelerometer_rotation 0; a shell settings put system user_rotation 1; sleep 3
a shell am force-stop "$PKG"
a shell am start -W -n "$PKG/me.zhanghai.android.files.viewer.video.VideoLauncherActivity" >/dev/null; sleep 2; guard
ui tap actionSettings >/dev/null; sleep 2
scroll_to "Hochskalieren|Upscaling" >/dev/null
ui tap "Hochskalieren|Upscaling" >/dev/null; sleep 1.5
ui has "$LABEL" || scroll_to "$LABEL" 3 >/dev/null # landscape A8: the sheet scrolls
ui tap "$LABEL" >/dev/null; sleep 1.5
back 1; back 1
for c in s_sita.mp4 s_sintel.mp4; do
    a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
        -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
    sleep 3; guard; ui wait "$c" 10 >/dev/null; ui tap "$c" >/dev/null
    wait_on viewer.video.VideoPlayerActivity 8 || { echo "FAIL player did not open"; continue; }
    sleep 9; guard
    [ "$(media_volume)" = 0 ] || { echo "ABORT volume"; exit 2; }
    screenshot "$out/shot_${TAG2}_${c%.mp4}.png" && echo "shot $TAG2 $c"
    a shell am force-stop "$PKG"; sleep 2
done
