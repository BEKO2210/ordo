#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Video notification (blocker before 1.0.0), WRITES: creates /storage/emulated/0/ordo-e2e-video-notify with a 60-s clip
# made by ffmpeg and removes it again; sets "Keep playing in the background" to "Sound only" and back to "Off".
# The media notification and the lock screen lead to viewer.video.background.VideoSessionActivity (no UI of its own,
# Theme.NoDisplay), which must bring the playing player's task back. Regression: tapping it crashed Ordo. Checked twice:
# with the player in front, and after Home (picture-in-picture or "Sound only"): no FATAL in the crash log, Ordo still
# runs, the player is in front again (or still in its picture-in-picture window) and the video still plays.
# How the activity is reached: `am start` works where the shell may start a non-exported activity (an emulator with
# `adb root`); with E2E_SHADE=1 the test instead opens the notification shade and taps Ordo Video's own notification
# (the only tap outside Ordo's screens, so only on request). Neither: SKIP, by hand (play a video with "Sound only",
# pull down the shade, tap the video's notification: the player comes back, no crash). Needs ffmpeg on the host.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/ordo-e2e-video-notify
CLIP=notify-clip.mp4
SESSION=me.zhanghai.android.files.viewer.video.background.VideoSessionActivity
tmp=$(mktemp -d)
settings_open() {
    a shell am start -W -n "$PKG/me.zhanghai.android.files.viewer.video.VideoLauncherActivity" >/dev/null; sleep 2
    guard; ui tap actionSettings >/dev/null; sleep 2
}
set_background() {
    settings_open
    scroll_to "Im Hintergrund weiterspielen|Keep playing in the background"
    ui tap "Im Hintergrund weiterspielen|Keep playing in the background" >/dev/null; sleep 1
    ui tap "$1" >/dev/null; sleep 1
    back 1; back 1
}
trap 'a shell cmd statusbar collapse >/dev/null 2>&1; set_background "Aus|Off"; a shell am force-stop "$PKG";
    a shell "rm -rf $DIR"; rm -rf "$tmp"' EXIT
command -v ffmpeg >/dev/null || { echo "SKIP video notification (no ffmpeg on the host)"; trap - EXIT; exit 0; }

ffmpeg -v error -f lavfi -i "testsrc=size=1280x720:rate=30:duration=60" -f lavfi -i "sine=frequency=440:duration=60" \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -b:a 128k -shortest "$tmp/$CLIP" ||
    echo "FAIL ffmpeg could not make $CLIP"
a shell "rm -rf $DIR; mkdir -p $DIR"
a push "$tmp/." "$DIR/" >/dev/null

wake; grant; a logcat -b crash -c
a shell am force-stop "$PKG"
session() {
    local d; d=$(a shell dumpsys media_session)
    { grep -A25 "id\.video\.[-0-9a-f]* $PKG" <<<"$d" || grep -A25 "package=$PKG" <<<"$d"; } | grep -m1 'state=PlaybackState'
}
playing() { session | grep -q 'state=PLAYING'; }
wait_playing() { local _; for _ in $(seq 1 16); do playing && return 0; sleep 0.5; done; return 1; }
open_clip() {
    a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
        -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
    sleep 3; guard; ui wait $CLIP 10 >/dev/null
    ui tap $CLIP >/dev/null
    wait_on viewer.video.VideoPlayerActivity 8
}
alive() { a shell pidof "$PKG" >/dev/null; }
pinned() {
    local d; d=$(a shell dumpsys activity activities)
    grep -B3 -A20 'Hist.*VideoPlayerActivity' <<<"$d" | grep -c 'mode=pinned\|windowingMode=pinned' >/dev/null ||
        grep -cE "Task\{.*A=[0-9]+:$PKG .*mode=pinned" <<<"$d" >/dev/null
}
player_back() { wait_on viewer.video.VideoPlayerActivity 6 || pinned; }
# fire: what a tap on the notification does. 0 = sent, 1 = no way to send it on this device.
fire() {
    local out
    out=$(a shell am start -W -n "$PKG/$SESSION" 2>&1)
    if ! grep -qiE 'error|exception|denial|not exported' <<<"$out"; then sleep 3; return 0; fi
    [ "${E2E_SHADE:-}" = 1 ] || return 1
    a shell cmd statusbar expand-notifications >/dev/null 2>&1; sleep 2
    # The notification names the clip; nothing else in the shade is ever tapped.
    if ! ui tap "$CLIP|${CLIP%.mp4}" >/dev/null 2>&1; then a shell cmd statusbar collapse >/dev/null 2>&1; return 1; fi
    sleep 3; a shell cmd statusbar collapse >/dev/null 2>&1; sleep 1
}

set_background "Nur Ton|Sound only"
a shell am force-stop "$PKG"; a logcat -b crash -c
open_clip; guard
check "the video plays" wait_playing
# The service's notification comes with the first playback: give it a moment.
check "Ordo Video's notification channel exists" bash -c \
    "for i in 1 2 3 4 5; do '$ADB' -s '$SERIAL' shell dumpsys notification | grep -q 'video_playback' && exit 0; sleep 1; done; exit 1"

# 1. Player in front: the session activity is opened over it.
if fire; then
    check "player in front: no crash after the notification's activity" test "$(crashes)" = 0
    check "player in front: Ordo still runs" alive
    check "player in front: the player is in front again" wait_on viewer.video.VideoPlayerActivity 6
    check "player in front: the video still plays" wait_playing
    # 2. Player left with Home (picture-in-picture, or "Sound only" where the device has none).
    a shell input keyevent KEYCODE_HOME; sleep 4
    if fire; then
        check "after Home: no crash after the notification's activity" test "$(crashes)" = 0
        check "after Home: Ordo still runs" alive
        check "after Home: the player is back (in front or in its picture-in-picture window)" player_back
        check "after Home: the video still plays" wait_playing
    else
        echo "SKIP after Home (the notification's activity could not be reached)"
    fi
    a shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1
else
    echo "SKIP the notification's activity cannot be reached (not exported; no adb root, E2E_SHADE not set): by hand as the header says"
    a shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1
fi
a shell am force-stop "$PKG"
finish
