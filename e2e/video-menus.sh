#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# farm: orient=once
# Video menus, WRITES: creates /storage/emulated/0/ordo-e2e-video-menus with a clip made by ffmpeg on the host (two
# chapters, a subtitle track) and removes it again. In portrait and in landscape: ⋮ opens "More", and every entry
# (Sound, Audio track, Subtitles, Adjust picture, Signal path, Video info, Chapters, Sleep timer) opens its sheet with
# the sheet's own words; the Speed button opens the speed sheet. With E2E_SHOTS=<dir> each screen is photographed
# there (only while Ordo is in front). The rotation is put back at the end. Media volume stays 0 (lib.sh).
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/ordo-e2e-video-menus
OUT=${E2E_SHOTS:-}
[ -n "$OUT" ] && mkdir -p "$OUT"
tmp=$(mktemp -d)
OLDAUTO=$(a shell settings get system accelerometer_rotation | tr -d '\r')
OLDROT=$(a shell settings get system user_rotation | tr -d '\r')
cleanup() {
    a shell am force-stop "$PKG"
    a shell settings put system user_rotation "${OLDROT:-0}"
    a shell settings put system accelerometer_rotation "${OLDAUTO:-1}"
    a shell "rm -rf $DIR"; rm -rf "$tmp"
}
trap cleanup EXIT
command -v ffmpeg >/dev/null || { echo "SKIP video menus (no ffmpeg on the host)"; exit 0; }

printf ';FFMETADATA1\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=20000\ntitle=Anfang\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=20000\nEND=40000\ntitle=Ende\n' \
    > "$tmp/chapters.txt"
printf '1\n00:00:00,000 --> 00:00:40,000\nUntertitel\n' > "$tmp/sub.srt"
ffmpeg -v error -f lavfi -i "testsrc=size=1280x720:rate=30:duration=40" -f lavfi -i "sine=frequency=440:duration=40" \
    -i "$tmp/sub.srt" -i "$tmp/chapters.txt" -map 0:v -map 1:a -map 2:s -map_metadata 3 -map_chapters 3 \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -c:s srt -metadata:s:a:0 language=deu \
    -metadata:s:s:0 language=deu -shortest "$tmp/01-menus.mkv" || echo "FAIL ffmpeg could not make 01-menus.mkv"
a shell "rm -rf $DIR; mkdir -p $DIR"
a push "$tmp/01-menus.mkv" "$DIR/" >/dev/null

wake; grant; a logcat -b crash -c
a shell am force-stop "$PKG"
shot() { [ -z "$OUT" ] || screenshot "$OUT/$1.png" >/dev/null; }
open_clip() {
    a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
        -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
    guard; ui wait "01-menus.mkv" 10 >/dev/null
    ui tap "01-menus.mkv" >/dev/null; wait_on viewer.video.VideoPlayerActivity 8; sleep 3
    media_key PAUSE 1.5
}
controls() { on viewer.video.VideoPlayerActivity || open_clip; ui has "Weitere Optionen|More options" || { read -r W H < <(screen_size); a shell input tap $((W / 2)) $((H / 2)); sleep 1; }; }
# entry ENTRY EXPECTED NAME: ⋮ › ENTRY shows EXPECTED; the screen is NAME; Back closes the sheet.
entry() {
    controls; video_menu "$1"
    check "$2 opens from ⋮ › ${1%%|*} ($orient)" ui wait "$3" 5
    shot "$4-$orient"; back 1
}

open_clip
for orient in portrait landscape; do
    a shell settings put system accelerometer_rotation 0
    a shell settings put system user_rotation "$([ $orient = portrait ] && echo 0 || echo 1)"; sleep 3 # deliberate: the rotation animation
    controls; ui tap "Weitere Optionen|More options" >/dev/null
    check "⋮ lists the sound entry ($orient)" ui wait "Klang|Sound" 5
    shot "more-$orient"
    back 1
    entry "Klang|Sound" "the sound sheet" "Nur für Videos – dein Musik-Klang bleibt, wie er ist|Only for videos – your music sound stays as it is" sound
    entry "Tonspur|Audio track" "the audio track sheet" "Ton-Synchronisation|Audio sync" audio-track
    entry "Untertitel|Subtitles" "the subtitle sheet" "Aus|Off" subtitles
    entry "Bild anpassen|Adjust picture" "the picture sheet" "Helligkeit|Brightness" picture
    entry "Signalweg|Signal path" "the signal path" "Signalweg|Signal path" signal
    entry "Videoinfos|Video info" "video info" "Auflösung|Resolution" info
    entry "Kapitel|Chapters" "the chapter list" "Anfang" chapters
    entry "Schlaf-Timer|Sleep timer" "the sleep timer" "15 Min.|15 min" sleep
    controls; ui tap "Tempo|Speed" >/dev/null
    check "the speed button opens the speed sheet ($orient)" ui wait "Wiedergabetempo|Playback speed" 5
    shot "speed-$orient"; back 1
done
check "no crash" test "$(crashes)" = 0
[ "$FAILS" = 0 ]
