#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Video player, phase 3, WRITES: creates /storage/emulated/0/ordo-e2e-video-bg with a 60-s clip made by ffmpeg and
# removes it again; sets "Keep playing in the background" to "Sound only" and back to "Off".
# Home while a video plays opens picture-in-picture (windowingMode=pinned) and it keeps playing (media_session); a
# device without picture-in-picture has no ⋮ entry for it. ⋮ › Sleep timer offers chips (15 min … End of video) and
# switches off again. With "Sound only" the screen going off keeps the video playing (state=PLAYING, position moves on)
# and Ordo Video's own notification channel is there; waking up shows the player again. Ordo Music's notification is not
# replaced (its queue: run e2e/audio-player.sh afterwards). Needs ffmpeg on the host.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/ordo-e2e-video-bg
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
trap 'set_background "Aus|Off"; a shell "rm -rf $DIR"; rm -rf "$tmp"' EXIT
command -v ffmpeg >/dev/null || { echo "SKIP video background (no ffmpeg on the host)"; trap - EXIT; exit 0; }

ffmpeg -v error -f lavfi -i "testsrc=size=1280x720:rate=30:duration=60" -f lavfi -i "sine=frequency=440:duration=60" \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -b:a 128k -shortest "$tmp/clip.mp4" ||
    echo "FAIL ffmpeg could not make clip.mp4"
a shell "rm -rf $DIR; mkdir -p $DIR"
a push "$tmp/." "$DIR/" >/dev/null

wake; grant; a logcat -b crash -c
a shell am force-stop "$PKG"
session() {
    local d; d=$(a shell dumpsys media_session)
    { grep -A25 "id\.video\.[-0-9a-f]* $PKG" <<<"$d" || grep -A25 "package=$PKG" <<<"$d"; } | grep -m1 'state=PlaybackState'
}
pos() { session | grep -oE '[{ ]position=[0-9]+' | head -1 | cut -d= -f2; }
playing() { session | grep -q 'state=PLAYING'; }
wait_playing() { local i; for i in $(seq 1 16); do playing && return 0; sleep 0.5; done; return 1; }
open_clip() {
    a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
        -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
    sleep 3; guard; ui wait clip.mp4 10 >/dev/null
    ui tap clip.mp4 >/dev/null
    wait_on viewer.video.VideoPlayerActivity 8
}
not_playing() { ! playing; }
no_pip_entry() { ! ui has "Bild-in-Bild|Picture-in-picture"; }
# mode=pinned stands on the task line above the activity (W90) or in its config lines further below (S21, Tab).
pinned() { a shell dumpsys activity activities | grep -B3 -A20 'Hist.*VideoPlayerActivity' | grep -q 'mode=pinned\|windowingMode=pinned'; }

# 1. Sleep timer: chips, never a slider, and off again.
open_clip; guard
check "the video plays" wait_playing
video_menu "Schlaf-Timer|Sleep timer"
check "the sleep timer offers 15 min" ui has "15 Min.|15 min"
check "the sleep timer offers the end of the video" ui has "Ende des Videos|End of video"
ui tap "30 Min.|30 min" >/dev/null; sleep 1
video_menu "Schlaf-Timer|Sleep timer"
check "the running timer says when it stops" eval 'ui texts | grep -qE "^(Stoppt in|Stops in) "'
ui tap "Aus|Off" >/dev/null; sleep 1

# 2. Picture-in-picture on Home (default on).
wait_playing
if a shell pm has-feature android.software.picture_in_picture | grep -q true; then
    a shell input keyevent KEYCODE_HOME; sleep 3
    check "Home opens picture-in-picture (windowingMode=pinned)" pinned
    check "the video keeps playing in picture-in-picture" wait_playing
    a shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1.5
    check "media keys reach the video in picture-in-picture" not_playing
else
    ui tap "Weitere Optionen|More options" >/dev/null; sleep 1
    check "without picture-in-picture there is no menu entry" no_pip_entry
    back 1
fi
a shell am force-stop "$PKG"; sleep 1

# 3. "Sound only": screen off, the video keeps playing; waking up shows the player again.
set_background "Nur Ton|Sound only"
open_clip; guard
check "the video plays with Sound only" wait_playing
# Screen off can lock a device with a PIN, and a test never unlocks one: only with E2E_ALLOW_SLEEP=1.
if [ "${E2E_ALLOW_SLEEP:-}" = 1 ]; then
before=$(pos)
a shell input keyevent KEYCODE_SLEEP; sleep 6
check "screen off: still state=PLAYING" playing
after=$(pos)
check "screen off: the position moves on (${before:-?} -> ${after:-?} ms)" test "${after:-0}" -gt "${before:-0}"
else
    echo "SKIP screen off (KEYCODE_SLEEP may lock the device: only with E2E_ALLOW_SLEEP=1)"
fi
# The channel comes with the service's first notification: give it a few seconds.
check "Ordo Video's own notification channel exists" bash -c \
    "for i in 1 2 3 4 5; do '$ADB' -s '$SERIAL' shell dumpsys notification | grep -q 'video_playback' && exit 0; sleep 1; done; exit 1"
wake
check "waking up shows the player" wait_on viewer.video.VideoPlayerActivity 6
a shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1
back 2

check "no crash" test "$(crashes)" = 0
exit $FAILS
