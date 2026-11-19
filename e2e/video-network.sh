#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Ordo Video on a TV, WRITES: creates /storage/emulated/0/ordo-e2e-vcast with a 40-s H.264 clip, a German SRT beside it
# and an AVI, and removes them again. Always: the player's toolbar has the TV button, its sheet speaks of the video
# (not of songs or sound effects). With CAST_TV=<name of a Chromecast/Google TV on this Wi-Fi>: casting the clip turns
# the player into the remote control ("Watch on this phone"), Pause/Play there and the volume keys work, "Watch on
# this phone" brings the clip back paused at the TV's place; an AVI never connects (its message is a toast).
# Not scriptable here (no SMB/SFTP server in this harness), by hand: a big film from an SMB/SFTP bookmark (buffer
# pill, seek back without new loading, Wi-Fi off/on resumes at the place, "Server not reachable"), the "Network" chip
# of Ordo Video's library, the subtitles shown on the TV. Needs ffmpeg on the host.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/ordo-e2e-vcast
TV=${CAST_TV:-}
tmp=$(mktemp -d); trap 'a shell "rm -rf $DIR"; rm -rf "$tmp"' EXIT
command -v ffmpeg >/dev/null || { echo "SKIP video network (no ffmpeg on the host)"; exit 0; }

ffmpeg -v error -f lavfi -i "testsrc=size=1280x720:rate=30:duration=40" -f lavfi -i "sine=frequency=440:duration=40" \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -b:a 96k -shortest "$tmp/01-tv-clip.mp4" \
    || echo "FAIL ffmpeg could not make the clip"
printf '1\r\n00:00:01,000 --> 00:00:30,000\r\nGrüße vom Fernseher\r\n' > "$tmp/01-tv-clip.de.srt"
ffmpeg -v error -f lavfi -i "testsrc=size=640x360:rate=25:duration=10" -c:v mpeg4 -vtag XVID -q:v 5 \
    "$tmp/02-tv-xvid.avi" || echo "FAIL ffmpeg could not make the AVI"
a shell "rm -rf $DIR; mkdir -p $DIR"
a push "$tmp/." "$DIR/" >/dev/null

wake; grant; a logcat -b crash -c
a shell am force-stop "$PKG"
absent() { ! ui has "$@"; }
settled() { local i; for i in $(seq 40); do ui has "$@" && return 0; sleep 0.5; done; return 1; }
open_clip() {
    a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
        -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
    sleep 3; guard
    ui wait "$1" 10 >/dev/null && ui tap "$1" >/dev/null
    sleep 3; guard
    # The controls hide after 4 s of playback; pause so the toolbar stays.
    a shell input keyevent KEYCODE_MEDIA_PAUSE >/dev/null; sleep 1
}

open_clip 01-tv-clip.mp4
check "the video player has a TV button" ui tap actionCast
check "its sheet offers this phone" settled "This phone|Dieses Handy"
check "its sheet speaks of this video" settled "only this video|nur dieses Video"
check "no sound-effects note for a film" absent "Sound effects only|Klangeffekte wirken"
if [ -z "$TV" ]; then
    echo "SKIP casting: set CAST_TV to the name of a Chromecast or Google TV on this Wi-Fi"
    back 1; back 1
    finish
fi
settled "$TV" >/dev/null 2>&1 || { fail "the sheet finds $TV"; back 1; back 1; finish; }
ui tap "$TV" >/dev/null
check "the player becomes the remote control" settled "Watch on this phone|Auf diesem Handy weiterschauen"
check "it names the TV" settled "Playing on $TV|Läuft auf $TV"
sleep 4
ui tap castPlayPause >/dev/null; sleep 2
check "Pause on the remote pauses the TV" settled "Paused|Pausiert"
for _ in 1 2; do a shell input keyevent KEYCODE_VOLUME_DOWN; sleep 0.3; done
check "the volume keys set the TV's volume" settled "Volume [0-9]+ %|Lautstärke [0-9]+ %"
ui tap castStop >/dev/null; sleep 2
check "the clip is back on the phone" absent "Watch on this phone|Auf diesem Handy weiterschauen"
back 1
# The AVI's "TVs can't play AVI videos" is a toast (not in the UI dump): only that nothing connects is checked.
open_clip 02-tv-xvid.avi
ui tap actionCast >/dev/null
settled "$TV" >/dev/null 2>&1 && ui tap "$TV" >/dev/null
sleep 3
check "an AVI never connects" absent "Watch on this phone|Auf diesem Handy weiterschauen"
back 1; back 1
check "no crash" [ "$(crashes)" = 0 ]
finish
