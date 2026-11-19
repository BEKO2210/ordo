#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Video sound, WRITES: creates /storage/emulated/0/ordo-e2e-video-sound with two clips made by ffmpeg on the host
# (H.264 with a 5.1 AAC track (German) and a stereo AAC track (English); H.264 with AC3 5.1) and removes it again.
# ⋮ › Sound opens the sheet with "Only for videos", the 5.1 note, Clear dialogue, Night mode and Mono; each switch on
# keeps the film playing (dumpsys media_session state=PLAYING), Strong too; ⋮ › Audio track lists both tracks with
# their language, "5.1" and "Stereo"; choosing English keeps it playing; "Sound later" twice says 100 ms later and it
# plays on; a tap on the value goes back to 0 ms. The AC3 clip on a device without an AC3 decoder marks its track as
# not playable. Everything is switched off again at the end. Whether it sounds right needs ears: SKIP, by hand.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/ordo-e2e-video-sound
tmp=$(mktemp -d); trap 'a shell "rm -rf $DIR"; rm -rf "$tmp"' EXIT
command -v ffmpeg >/dev/null || { echo "SKIP video sound (no ffmpeg on the host)"; exit 0; }

ffmpeg -v error -f lavfi -i "testsrc=size=1280x720:rate=30:duration=30" \
    -f lavfi -i "sine=frequency=440:duration=30" -f lavfi -i "sine=frequency=660:duration=30" \
    -filter_complex "[1:a]pan=5.1|FL=c0|FR=c0|FC=c0|LFE=c0|BL=c0|BR=c0[s51]" \
    -map 0:v -map "[s51]" -map 2:a -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -b:a 192k \
    -metadata:s:a:0 language=deu -metadata:s:a:1 language=eng -shortest "$tmp/01-surround.mp4" \
    || echo "FAIL ffmpeg could not make 01-surround.mp4"
ffmpeg -v error -f lavfi -i "testsrc=size=640x360:rate=25:duration=10" -f lavfi -i "sine=frequency=550:duration=10" \
    -ac 6 -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a ac3 -shortest "$tmp/02-ac3.mkv" \
    || echo "FAIL ffmpeg could not make 02-ac3.mkv"
a shell "rm -rf $DIR; mkdir -p $DIR"
a push "$tmp/." "$DIR/" >/dev/null

wake; grant; a logcat -b crash -c
a shell am force-stop "$PKG"
session() {
    local d; d=$(a shell dumpsys media_session)
    { grep -A25 "id\.video\.[-0-9a-f]* $PKG" <<<"$d" || grep -A25 "package=$PKG" <<<"$d"; } | grep -m1 'state=PlaybackState'
}
playing() { session | grep -q 'state=PLAYING'; }
wait_playing() { local i; for i in $(seq 1 16); do playing && return 0; sleep 0.5; done; return 1; }
open_clip() {
    a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
        -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
    sleep 3; guard; ui wait "$1" 10 >/dev/null
    ui tap "$1" >/dev/null; wait_on viewer.video.VideoPlayerActivity 8
}
# The controls hide while a film plays: a tap in the middle brings them back, then ⋮ › entry.
menu() {
    read -r W H < <(screen_size)
    ui has "Weitere Optionen|More options" || { a shell input tap $((W / 2)) $((H / 2)); sleep 1; }
    ui tap "Weitere Optionen|More options" >/dev/null; sleep 1
    ui tap "$1" >/dev/null; sleep 1.5
}
switch_on() { ui tap "$1" >/dev/null; sleep 1.5; }

# 1. The sound sheet.
open_clip 01-surround.mp4
check "the surround clip plays" wait_playing
menu "Klang|Sound"
check "the sound sheet opens with its own settings" ui has "Nur für Videos|Only for videos"
check "a 5.1 track is said to be mixed for two speakers" ui has "5.1-Ton wird|5.1 sound is mixed"
switch_on "Dialog-Verstärker|Clear dialogue"
check "with Clear dialogue on it plays on" wait_playing
ui tap "Stark|Strong" >/dev/null; sleep 1.5
check "with Clear dialogue strong it plays on" wait_playing
switch_on "Nachtmodus|Night mode"
check "with Night mode on it plays on" wait_playing
swipe_up
switch_on "Mono-Audio|Mono audio"
check "with Mono on it plays on" wait_playing
ui tap "Mehr links|More left" >/dev/null 2>&1; sleep 1
check "balance and all effects at once: still playing" wait_playing
check "no crash with every effect on" test "$(crashes)" = 0
back

# 2. The audio track sheet and A/V sync.
menu "Tonspur|Audio track"
check "both tracks are listed by language" eval 'ui has "Deutsch|German" && ui has "Englisch|English"'
check "channels are named" eval 'ui has "5.1" && ui has "Stereo"'
ui tap "Englisch|English" >/dev/null; sleep 2
check "the English track plays" wait_playing
menu "Tonspur|Audio track"
ui tap "Ton später|Sound later" >/dev/null; sleep 1
ui tap "Ton später|Sound later" >/dev/null; sleep 1
check "two steps later say 100 ms" ui has "Ton 100 ms später|Sound 100 ms later"
check "with the sound 100 ms later it plays on" wait_playing
ui tap "Ton 100 ms später|Sound 100 ms later" >/dev/null; sleep 1
check "a tap on the value puts it back in sync" ui has "Synchron|In sync"
back

# 3. Everything off again, so the next run and the owner start clean.
menu "Klang|Sound"
switch_on "Dialog-Verstärker|Clear dialogue"
switch_on "Nachtmodus|Night mode"
swipe_up
switch_on "Mono-Audio|Mono audio"
ui tap "Mehr rechts|More right" >/dev/null 2>&1; sleep 1
back; back 2

# 4. An AC3 track this device cannot decode is marked, not hidden.
open_clip 02-ac3.mkv
sleep 2
menu "Tonspur|Audio track"
if ui has "nicht abspielbar|can't play"; then
    pass "the AC3 track is marked as not playable here"
else
    check "the AC3 track plays (this device decodes AC3)" eval 'ui has "AC3" && wait_playing'
fi
back; back 2

echo "SKIP how dialogue, night mode and the 5.1 mix sound (by ear, with headphones and the speaker)"
check "no crash in the whole run" test "$(crashes)" = 0
a shell am force-stop "$PKG"
finish
