#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Ordo Video's pro seeking (viewer/video/seek), WRITES: creates /storage/emulated/0/ordo-e2e-seek with a 60-s H.264
# clip at 30 fps carrying three MP4 chapters (made by ffmpeg on the host) and removes it and the saved frames again.
# ⋮ offers "Chapters" and the sheet lists all three, a tap on the third jumps to 0:40; "Frame by frame" pauses and
# its next/previous buttons step about one frame (33 ms); the keys "." and "," step too, space plays and pauses;
# A-B: A, then B 3 s later, plays and comes back to A; "Save frame" writes a JPEG to Pictures/Ordo; the frame
# buttons keep 48 dp. Not scriptable from adb: the preview frame while dragging (no accessibility node), pinch zoom
# (adb has no two-finger input): SKIP, by hand. Needs ffmpeg on the host.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/ordo-e2e-seek
SHOTS=/storage/emulated/0/Pictures/Ordo
tmp=$(mktemp -d); trap 'a shell "rm -rf $DIR $SHOTS/01-chapters_*"; rm -rf "$tmp"' EXIT
command -v ffmpeg >/dev/null || { echo "SKIP video seek (no ffmpeg on the host)"; exit 0; }

cat > "$tmp/chapters.txt" <<'EOF'
;FFMETADATA1
[CHAPTER]
TIMEBASE=1/1000
START=0
END=20000
title=Anfang
[CHAPTER]
TIMEBASE=1/1000
START=20000
END=40000
title=Mitte
[CHAPTER]
TIMEBASE=1/1000
START=40000
END=60000
title=Schluss
EOF
ffmpeg -v error -f lavfi -i "testsrc=size=1280x720:rate=30:duration=60" -f lavfi -i "sine=frequency=440:duration=60" \
    -i "$tmp/chapters.txt" -map_metadata 2 -map_chapters 2 -map 0 -map 1 -c:v libx264 -preset veryfast -g 30 \
    -pix_fmt yuv420p -c:a aac -shortest "$tmp/01-chapters.mp4" || echo "FAIL ffmpeg could not make the clip"
rm "$tmp/chapters.txt"
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
not_playing() { ! playing; }
pause() { a shell input keyevent KEYCODE_MEDIA_PAUSE >/dev/null; sleep 1.5; }
within() { [ "$1" -ge "$2" ] && [ "$1" -le "$3" ]; }
dp() { echo $(($1 * $(a shell wm density | tail -1 | sed 's/.*: //' | tr -d '\r') / 160)); }
touch_target() {
    local l t r b min; min=$(dp "$2")
    read -r l t r b < <(ui bounds "$1" | head -1) || return 1
    [ $((r - l)) -ge "$min" ] && [ $((b - t)) -ge "$min" ]
}
menu() { ui tap "Weitere Optionen|More options" >/dev/null; sleep 1; ui wait "$1" 3 >/dev/null || scroll_to "$1" 3 >/dev/null; ui tap "$1" >/dev/null; sleep 1.5; }

a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
    -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
sleep 3; guard; ui wait 01-chapters.mp4 10 >/dev/null
ui tap 01-chapters.mp4 >/dev/null
check "the clip opens Ordo's player" wait_on viewer.video.VideoPlayerActivity 8
sleep 2; pause

# 1. Chapters.
menu "Kapitel|Chapters"
check "the chapters sheet lists the first chapter" ui wait "Anfang" 5
check "… and the third" ui has "Schluss"
ui tap "Schluss" >/dev/null; sleep 1.5
p1=$(pos)
check "a tap on the third chapter jumps to 0:40 ($p1 ms)" within "${p1:-0}" 39500 41000

# 2. Frame by frame: pauses, steps about one frame each way.
menu "Einzelbild|Frame by frame"
check "frame by frame shows its next-frame button" ui wait "Nächstes Bild|Next frame" 5
check "the frame buttons keep 48 dp" touch_target "Nächstes Bild|Next frame" 48
check "frame by frame pauses" not_playing
f0=$(pos); ui tap "Nächstes Bild|Next frame" >/dev/null; sleep 1; f1=$(pos)
check "next frame moves about 33 ms ($f0 -> $f1)" within $(( ${f1:-0} - ${f0:-0} )) 20 50
ui tap "Vorheriges Bild|Previous frame" >/dev/null; sleep 1; f2=$(pos)
check "previous frame comes back ($f1 -> $f2)" within $(( ${f1:-0} - ${f2:-0} )) 20 50
ui tap "Einzelbild beenden|End frame by frame" >/dev/null; sleep 1

# 3. Keys: "." and "," step a frame, space plays and pauses.
k0=$(pos); a shell input keyevent KEYCODE_PERIOD; sleep 1; k1=$(pos)
check "the . key steps a frame ($k0 -> $k1)" within $(( ${k1:-0} - ${k0:-0} )) 20 50
a shell input keyevent KEYCODE_COMMA; sleep 1; k2=$(pos)
check "the , key steps back ($k1 -> $k2)" within $(( ${k1:-0} - ${k2:-0} )) 20 50
a shell input keyevent KEYCODE_SPACE; sleep 1.5
check "space plays" playing
a shell input keyevent KEYCODE_SPACE; sleep 1.5
check "space pauses again" not_playing

# 4. A-B: A here, B about 3 s later; playing on for 5 s comes back to A instead of passing B.
pa=$(pos); menu "Schleifenstart A setzen|Set loop start \\(A\\)"
a shell input keyevent KEYCODE_MEDIA_PLAY; sleep 3; pause
pb=$(pos); menu "Schleifenende B setzen|Set loop end \\(B\\)"
a shell input keyevent KEYCODE_MEDIA_PLAY; sleep 5; pause
p4=$(pos)
check "the A-B loop keeps playback between A and B ($pa .. $pb: $p4 ms)" \
    within "${p4:-0}" $(( ${pa:-0} - 300 )) $(( ${pb:-0} + 300 ))
menu "A-B-Schleife beenden|End A-B loop"
ui tap "Weitere Optionen|More options" >/dev/null; sleep 1
check "after ending the loop the menu offers A again" scroll_to "Schleifenstart A setzen|Set loop start \\(A\\)"
back 1

# 5. Save frame: a JPEG in Pictures/Ordo.
menu "Standbild speichern|Save frame"
sleep 2
check "Save frame wrote a JPEG to Pictures/Ordo" \
    test "$(a shell "ls $SHOTS/01-chapters_*.jpg 2>/dev/null | wc -l" | tr -d '\r ')" -ge 1

echo "SKIP preview frame while dragging the bar or swiping (no accessibility node; check by hand or screenshot)"
echo "SKIP pinch zoom, pan, two-finger tap reset (adb input has no two-finger gestures; check by hand)"
a shell am force-stop "$PKG"
finish
