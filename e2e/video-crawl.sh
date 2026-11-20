#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Ordo Video crawler (owner, 20.11.2026: "press every button, also rotate, plug in"), WRITES: creates
# /storage/emulated/0/ordo-e2e-crawl with a 10-min clip and a subtitle file made by ffmpeg and removes it again.
# Runs video-crawl.py in portrait and in landscape: every clickable and long-clickable control of the library, its
# settings, the paused player and every sheet behind the player's ⋮ menu is pressed once (checkable ones are put
# back), each sheet is scrolled inside itself on its right half (where the picture's volume gesture lives) and the
# media volume must stay 0; then the screen is rotated with the playing film and with every sheet open. After every
# step: Ordo in front, no FATAL/ANR, volume 0 (else muted again and stopped). Destructive or outward controls
# (delete, share, cast, open with, save frame, reset) are listed as SKIP. Headphones plugged in or out can't be
# simulated over adb without root: listed as a check by hand. Silent clip: no sound at any step.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/ordo-e2e-crawl
tmp=$(mktemp -d)
auto=$(a shell settings get system accelerometer_rotation | tr -d '\r'); rot=$(a shell settings get system user_rotation | tr -d '\r')
trap 'a shell "rm -rf $DIR"; rm -rf "$tmp"; a shell settings put system user_rotation "${rot:-0}"; a shell settings put system accelerometer_rotation "${auto:-1}"' EXIT
command -v ffmpeg >/dev/null || { echo "SKIP crawl (no ffmpeg on the host)"; exit 0; }
ffmpeg -v error -f lavfi -i "testsrc=size=1280x720:rate=30:duration=600" -f lavfi -i "anullsrc=r=48000:cl=stereo" \
    -t 600 -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -b:a 64k "$tmp/crawl.mp4" || { fail "ffmpeg"; finish; }
printf '1\n00:00:01,000 --> 00:09:00,000\nUntertitel für den Crawler\n' > "$tmp/crawl.srt"
a shell "rm -rf $DIR; mkdir -p $DIR"; a push "$tmp/." "$DIR/" >/dev/null
wake; grant; a logcat -b crash -c
for o in ${CRAWL_ORIENT:-0 1}; do
    python3 "$HERE/video-crawl.py" "$SERIAL" "$PKG" "$DIR" "$o" || { [ $? = 2 ] && exit 2; FAILS=$((FAILS + 1)); }
done
echo "SKIP headphones plugged in / pulled out and Bluetooth route changes: adb has no plug simulation without root (cmd audio offers none; ACTION_HEADSET_PLUG and AUDIO_BECOMING_NOISY are system-only), check by hand"
finish
