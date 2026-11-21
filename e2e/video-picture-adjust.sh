#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# farm: needs=shots
# "Bild anpassen" (⋮ › Adjust picture), WRITES: creates /storage/emulated/0/ordo-e2e-adjust with clips made by ffmpeg
# on the host and removes it again: 01-full.mp4 (1080p progressive H.264, as big as a phone screen, so no enhancement)
# and 02-interlaced.mp4 (576i H.264, x264 interlaced=1). The sheet has the five rows with −/+ (never a slider); two
# taps on "Brightness up" show +2 and the pass runs although the video is not smaller than the screen (log: APPLY);
# the value is remembered after reopening; "Reset" goes back to 0. The interlaced clip says it is interlaced and runs
# the pass for deinterlacing by itself. Screenshots before/after for a look by hand. Silent: no volume step.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/ordo-e2e-adjust
TAG=OrdoVideoPicture
out="${TMPDIR:-/tmp}/ordo-e2e-adjust"; mkdir -p "$out"
tmp=$(mktemp -d); trap 'a shell "rm -rf $DIR"; a shell setprop log.tag.$TAG INFO; rm -rf "$tmp"' EXIT
command -v ffmpeg >/dev/null || { echo "SKIP picture adjust (no ffmpeg on the host)"; exit 0; }

ffmpeg -v error -f lavfi -i "testsrc2=size=1920x1080:rate=30:duration=20" -c:v libx264 -preset veryfast \
    -pix_fmt yuv420p "$tmp/01-full.mp4" || { fail "ffmpeg 01-full.mp4"; finish; }
ffmpeg -v error -f lavfi -i "testsrc2=size=720x576:rate=25:duration=20" -c:v libx264 -preset veryfast \
    -pix_fmt yuv420p -flags +ildct+ilme -x264-params interlaced=1 "$tmp/02-interlaced.mp4" \
    || { fail "ffmpeg 02-interlaced.mp4"; finish; }
a shell "rm -rf $DIR; mkdir -p $DIR"
a push "$tmp/." "$DIR/" >/dev/null

wake; grant; a logcat -b crash -c
a shell am force-stop "$PKG"
a shell setprop log.tag.$TAG DEBUG
log() { a logcat -d -s "$TAG:D" | tr -d '\r'; }
pause() { media_key PAUSE 1.5; }
open_video() {
    a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
        -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
    guard; ui wait "$1" 10 >/dev/null
    ui tap "$1" >/dev/null; wait_on viewer.video.VideoPlayerActivity 8; media_wait PLAY 3; pause
}
sheet() {
    ui tap "Weitere Optionen|More options" >/dev/null
    ui tap "Bild anpassen|Adjust picture" >/dev/null; sleep 1.5
}

# 1. The sheet: five rows in steps, deinterlacing, reset.
a logcat -c
open_video 01-full.mp4
screenshot "$out/full-before.png"
sheet
check "the sheet opens" ui wait "Helligkeit|Brightness" 5
for row in "Kontrast|Contrast" "Sättigung|Saturation" "Farbtemperatur|Colour temperature" "Schärfe|Sharpness" \
    "Zeilensprung entfernen|Remove interlacing" "Automatisch|Automatic"; do
    check "the sheet has $row" ui has "$row"
done
check "a 1080p progressive clip is not called interlaced" ui has \
    "Dieses Video hat Vollbilder: Automatisch lässt es, wie es ist.|This video has full frames: automatic leaves it."

# 2. Two steps up: +2, and the pass runs on a video that is not smaller than the screen.
ui tap "Helligkeit erhöhen|Brightness up" >/dev/null
ui tap "Helligkeit erhöhen|Brightness up" >/dev/null
check "two taps show +2" ui see "+2"
sleep 3
check "the pass is set although the enhancement has nothing to do" eval 'log | grep -E -- "-> (ENGAGE|APPLY)"'
back 1
screenshot "$out/full-brighter.png"
echo "INFO compare $out/full-before.png and $out/full-brighter.png (brighter)"

# 3. Remembered per video, then reset.
back 2
open_video 01-full.mp4
sheet
check "the step is remembered for the video" ui has "+2"
ui tap "Zurücksetzen|Reset" >/dev/null
check "reset goes back to 0" eval '! ui has "+2"'
back 1; back 2

# 4. An interlaced clip is detected and deinterlaced by itself.
a logcat -c
open_video 02-interlaced.mp4
sheet
check "the interlaced clip is detected" ui has \
    "Dieses Video hat Zeilensprung: Automatisch entfernt ihn.|This video is interlaced: automatic removes it."
check "and runs the pass" eval 'log | grep -E -- "-> (ENGAGE|APPLY)"'
back 1
screenshot "$out/interlaced.png"
echo "INFO look for comb lines on moving edges: $out/interlaced.png"
back 2

check "no crash" test "$(crashes)" = 0
a shell am force-stop "$PKG"
finish
