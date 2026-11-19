#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Video player, phase 1, WRITES: creates /storage/emulated/0/ordo-e2e-video with files made by ffmpeg on the host (two
# 30-s H.264/AAC 1080p clips with the time burnt in, a 0-byte .mp4, an AVI with MPEG-4 ASP/Xvid) and removes it again.
# A tap in the file list opens Ordo's player and it plays (dumpsys media_session); a tap, a quick swipe and a scroll on
# the seek bar never move it, 400 ms held and dragged to 3/4 does; a double tap in the left 28 % jumps 10 s back, three
# quick taps 20 s, the right 28 % ahead; the lock stops taps until its button is held; closing at 0:12 and opening again
# offers "Resume at 0:12", a tap goes there; "Next video" at the end opens the next of the folder; the 0-byte file and
# (if the device cannot play it) the AVI show the message with "Open with another app" instead of crashing; an
# external file:// intent is refused, a content:// one with a read grant plays; the rotate button (phone) keeps
# position and playback; at the end it stops with "Watch again"/"Next video"; one left-edge swipe goes back; a
# vertical swipe on the right half changes the volume; Ordo Video's own colour (Amber) changes the play button's
# tint and is put back to "Same as Ordo"; the settings page and the player's "Video info" open; buttons keep 48 dp, the play button 88 dp on a tablet. Headphones and audio focus are not
# scriptable from adb: SKIP, by hand. Needs ffmpeg on the host.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/ordo-e2e-video
PLAYER=me.zhanghai.android.files.viewer.video.VideoPlayerActivity
tmp=$(mktemp -d); trap 'a shell "rm -rf $DIR"; rm -rf "$tmp"' EXIT
command -v ffmpeg >/dev/null || { echo "SKIP video player (no ffmpeg on the host)"; exit 0; }

# testsrc burns a running time into the picture, so a screenshot shows where the clip is.
for n in 01 02; do
    ffmpeg -v error -f lavfi -i "testsrc=size=1920x1080:rate=30:duration=30" \
        -f lavfi -i "sine=frequency=$((330 + n * 110)):duration=30" -c:v libx264 -preset veryfast -pix_fmt yuv420p \
        -c:a aac -b:a 128k -shortest "$tmp/$n-clip.mp4" || echo "FAIL ffmpeg could not make $n-clip.mp4"
done
: > "$tmp/03-empty.mp4"
ffmpeg -v error -f lavfi -i "testsrc=size=640x360:rate=25:duration=10" -f lavfi -i "sine=frequency=550:duration=10" \
    -c:v mpeg4 -vtag XVID -q:v 5 -c:a libmp3lame -b:a 128k -shortest "$tmp/04-xvid.avi" || echo "FAIL ffmpeg could not make the AVI"
a shell "rm -rf $DIR; mkdir -p $DIR"
a push "$tmp/." "$DIR/" >/dev/null

wake; grant; a logcat -b crash -c
# No music session in the way: the video session is the only one of Ordo's.
a shell am force-stop "$PKG"
pause() { a shell input keyevent KEYCODE_MEDIA_PAUSE >/dev/null; sleep 1.5; }
# The video session's state line; its ID starts with "video." (messen: how dumpsys prints the tag), else Ordo's first
# session.
session() {
    local d; d=$(a shell dumpsys media_session)
    { grep -A25 "id\.video\.[-0-9a-f]* $PKG" <<<"$d" || grep -A25 "package=$PKG" <<<"$d"; } | grep -m1 'state=PlaybackState'
}
pos() { session | grep -oE '[{ ]position=[0-9]+' | head -1 | cut -d= -f2; }
playing() { session | grep -q 'state=PLAYING'; }
wait_playing() { local i; for i in $(seq 1 16); do playing && return 0; sleep 0.5; done; return 1; }
not_on() { ! on "$@"; }
open_folder() {
    a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
        -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
    sleep 3; guard; ui wait "$1" 10 >/dev/null
}
dp() { echo $(($1 * $(a shell wm density | tail -1 | sed 's/.*: //' | tr -d '\r') / 160)); }
# touch_target ID DP: the control's box is at least DP x DP.
touch_target() {
    local l t r b min; min=$(dp "$2")
    read -r l t r b < <(ui bounds "$1" | head -1) || return 1
    [ $((r - l)) -ge "$min" ] && [ $((b - t)) -ge "$min" ]
}
within() { [ "$1" -ge "$2" ] && [ "$1" -le "$3" ]; }

# 1. A tap in the file list opens Ordo's player, and it plays.
open_folder 01-clip.mp4
ui tap 01-clip.mp4 >/dev/null
check "a video opens Ordo's player" wait_on viewer.video.VideoPlayerActivity 8
guard
check "the video plays (media_session state=PLAYING)" wait_playing
pause
check "paused, the controls stay up" ui has playPause
check "play/pause keeps its 48 dp touch target" touch_target playPause 48
check "the lock keeps its 48 dp touch target" touch_target lock 48
check "speed keeps its 48 dp touch target" touch_target speed 48
smallest=$(a shell dumpsys window displays | grep -oE 'sw[0-9]+dp' | head -1 | tr -dc 0-9)
if [ -n "$smallest" ] && [ "$smallest" -ge 600 ]; then
    check "on a tablet the play button is 88 dp" touch_target playPause 87
fi

# 2. The seek bar: MDC hides the slider's own node, so it lies between the two times.
read -r _ y1 x1 y2 < <(ui bounds elapsed | head -1)
read -r x2 _ _ _ < <(ui bounds duration | head -1)
y=$(((y1 + y2) / 2)); w=$((x2 - x1)); xa=$((x1 + w / 4)); xb=$((x1 + w * 3 / 4))
moved_less() { [ $(($2 - $1)) -lt 2000 ] && [ $(($1 - $2)) -lt 2000 ]; }
p0=$(pos); a shell input tap $xb $y; sleep 1.5; p1=$(pos)
check "a tap on the bar does not seek ($p0 -> $p1 ms)" moved_less "$p0" "$p1"
a shell input swipe $xa $y $xb $y 120; sleep 1.5; p2=$(pos)
check "a quick swipe along the bar does not seek ($p1 -> $p2 ms)" moved_less "$p1" "$p2"
a shell input swipe $xb $((y - 200)) $xb $((y + 200)) 200; sleep 1.5; p3=$(pos)
check "a vertical swipe over the bar does not seek ($p2 -> $p3 ms)" moved_less "$p2" "$p3"
a shell "input motionevent DOWN $xa $y; sleep 0.4; input motionevent MOVE $((xa + w / 8)) $y; \
    input motionevent MOVE $((xa + w / 4)) $y; input motionevent MOVE $xb $y; sleep 0.3; input motionevent UP $xb $y"
sleep 1.5; p4=$(pos)
# 3/4 of 30 s is 22.5 s; the slider's track inset leaves a little either way.
check "held 400 ms and dragged, the bar seeks to three quarters ($p4 ms)" within "${p4:-0}" 19000 26000

# 3. Tap zones on the picture, in the middle height, away from the controls and the system's gesture edges.
read -r W H < <(screen_size)
left=$((W * 14 / 100)); right=$((W * 86 / 100)); mid=$((H / 2))
a shell "input tap $left $mid; input tap $left $mid; input tap $left $mid"; sleep 1.5; p5=$(pos)
check "three quick taps on the left 28 % jump 20 s back ($p4 -> $p5 ms)" within $((p4 - p5)) 18000 22000
a shell "input tap $right $mid; sleep 0.12; input tap $right $mid"; sleep 1.5; p6=$(pos)
check "a double tap on the right 28 % jumps 10 s ahead ($p5 -> $p6 ms)" within $((p6 - p5)) 8000 12000

# The lock: everything goes but "hold to unlock"; taps do nothing until it is held.
pause
ui tap lock >/dev/null; sleep 1
check "locked, only the unlock button shows" eval 'ui has unlock && ! ui has playPause'
a shell "input tap $right $mid; sleep 0.12; input tap $right $mid"; sleep 1.5; p7=$(pos)
check "locked, a double tap does not seek ($p6 -> $p7 ms)" moved_less "$p6" "$p7"
read -r l t r b < <(ui bounds unlock | head -1)
a shell input swipe $(((l + r) / 2)) $(((t + b) / 2)) $(((l + r) / 2)) $(((t + b) / 2)) 900; sleep 1.5
check "holding the unlock button brings the controls back" ui has playPause

# 4. Resume: closed at ~0:12 (more than 10 s, less than 95 %), opened again it offers that place.
back 2
check "back closes the player" not_on viewer.video.VideoPlayerActivity
# Paused at once: the pill stays while paused, and a playing screen is slow to read on a budget tablet (the W90 run
# found the pill only after it had gone, 5 s into playback).
ui tap 01-clip.mp4 >/dev/null; wait_on viewer.video.VideoPlayerActivity 8
a shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1
check "opened again, it offers to resume" ui wait resumePill 6
check "the pill says where" eval "ui texts | grep -qE 'Weiter bei|Resume at'"
ui tap resumePill >/dev/null; sleep 1.5; pause; p8=$(pos)
check "a tap on the pill goes there ($p7 -> $p8 ms)" within "${p8:-0}" $((${p7:-0} - 1500)) $((${p7:-0} + 3000))

# Ordo Video (the library behind the launcher icon): the clip just left is under "Continue watching" and opens at
# its place without the pill. MediaStore must know the clips first.
for n in 01 02; do
    a shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$DIR/$n-clip.mp4" >/dev/null 2>&1
done
back 2; sleep 3
a shell am start -W -n "$PKG/me.zhanghai.android.files.viewer.video.VideoLauncherActivity" >/dev/null; sleep 3; guard
check "Ordo Video opens its library" on viewer.video.VideoLauncherActivity
check "the library offers Continue watching" ui wait "Weiterschauen|Continue watching" 8
check "the library lists the clip" ui has 01-clip
ui tap 01-clip >/dev/null; wait_on viewer.video.VideoPlayerActivity 8; sleep 2; pause; p8b=$(pos)
check "from Continue watching it starts at its place ($p8b ms)" within "${p8b:-0}" $((${p7:-0} - 1500)) $((${p7:-0} + 4000))
check "and offers no pill" eval '! ui has resumePill'
back 2
check "back returns to the library" on viewer.video.VideoLauncherActivity
back 2
open_folder 01-clip.mp4
ui tap 01-clip.mp4 >/dev/null; wait_on viewer.video.VideoPlayerActivity 8; sleep 1
a shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1
ui tap resumePill >/dev/null 2>&1; sleep 1.5

# 5. The end of a clip: by default ("Stop at the end", the owner's choice) the player stays on the last frame with
# "Watch again" and "Next video"; Next video opens the next clip of the folder (titles without the extension).
a shell input keyevent KEYCODE_MEDIA_PLAY; sleep 22
check "at the end the player stops with Watch again" ui wait endReplay 6
check "and offers the next video" ui has endNext
ui tap endNext >/dev/null; sleep 2; pause
check "Next video opens the next one of the folder" ui has 02-clip

# Swipes on the picture (plan P4 goals 2 and 1): one swipe from the left edge is the system's back, the first time;
# a vertical swipe in the right half changes the media volume (restored afterwards).
read -r W H < <(screen_size)
vol() { a shell cmd media_session volume --stream 3 --get 2>/dev/null | grep -oE 'volume is [0-9]+' | grep -oE '[0-9]+'; }
# Back to the volume before the test with the volume keys: on the W90 and the Pixel neither --set nor --adjust of
# `cmd media_session volume` changed it reliably, the keys did. The player is in front, so they act on media.
restore_volume() {
    local i v
    for i in $(seq 1 30); do
        v=$(vol); [ -z "$v" ] || [ "$v" = "$1" ] && break
        if [ "$v" -gt "$1" ]; then a shell input keyevent KEYCODE_VOLUME_DOWN
        else a shell input keyevent KEYCODE_VOLUME_UP; fi
        sleep 0.3
    done
    v=$(vol); [ "$v" = "$1" ] || echo "INFO volume left at $v instead of $1"
}
v0=$(vol)
a shell input swipe $((W * 80 / 100)) $((H * 70 / 100)) $((W * 80 / 100)) $((H * 35 / 100)) 400; sleep 1; v1=$(vol)
if [ -n "$v0" ] && [ -n "$v1" ]; then
    check "a vertical swipe in the right half raises the volume ($v0 -> $v1)" test "$v1" -gt "$v0" -o "$v0" = "$(a shell cmd media_session volume --stream 3 --get | grep -oE 'max [0-9]+' | grep -oE '[0-9]+')"
    restore_volume "$v0"
else
    echo "SKIP volume swipe (cmd media_session volume is not readable here)"
fi
if [ "$(a shell settings get secure navigation_mode | tr -d '\r')" = 2 ]; then
    a shell input swipe 2 $((H / 2)) $((W / 3)) $((H / 2)) 250; sleep 2
    check "one edge swipe from the left leaves the player (back gesture)" not_on viewer.video.VideoPlayerActivity
else
    echo "SKIP edge-swipe back (three-button navigation)"
    back 2
fi

# 7. Files the player cannot play explain themselves and offer another app.
open_folder 03-empty.mp4
ui tap 03-empty.mp4 >/dev/null; wait_on viewer.video.VideoPlayerActivity 8
check "a 0-byte video shows the message with Open with another app" ui wait errorOpenWith 8
system_focus() {
    local i; for i in 1 2 3 4 5 6; do
        a shell dumpsys window | grep -m1 mCurrentFocus | grep -qv "$PKG" && return 0; sleep 0.5
    done
    return 1
}
ui tap errorOpenWith >/dev/null
check "Open with another app hands over to the system chooser" system_focus
a shell input keyevent KEYCODE_BACK; sleep 1; back 2
open_folder 04-xvid.avi
ui tap 04-xvid.avi >/dev/null; wait_on viewer.video.VideoPlayerActivity 8
avi() { local i; for i in $(seq 1 10); do playing && return 0; ui has errorOpenWith && return 0; sleep 1; done; return 1; }
check "the Xvid AVI plays or explains itself ($(playing && echo plays || echo message))" avi
back 2

# 6. External callers: file:// is refused, content:// with a read grant plays.
a shell am force-stop "$PKG"
a shell am start -W -a android.intent.action.VIEW -d "file://$DIR/01-clip.mp4" -t video/mp4 \
    --es me.zhanghai.android.files.extra.PATH_URI "file://$DIR/02-clip.mp4" -n "$PKG/$PLAYER" >/dev/null; sleep 2
check "an external file:// intent is refused and the player closes" not_on viewer.video.VideoPlayerActivity
a shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$DIR/02-clip.mp4" >/dev/null 2>&1
sleep 3
id=$(a shell content query --uri content://media/external/video/media --projection _id \
    --where "_display_name=\'02-clip.mp4\'" 2>/dev/null | grep -oE '_id=[0-9]+' | head -1 | cut -d= -f2)
if [ -n "$id" ]; then
    a shell am start -W -a android.intent.action.VIEW -d "content://media/external/video/media/$id" -t video/mp4 \
        --grant-read-uri-permission -n "$PKG/$PLAYER" >/dev/null; sleep 2
    check "an external content:// intent with a read grant plays" wait_playing
    pause; back 2
else
    echo "SKIP content:// intent (MediaStore has not indexed the clip)"
fi

# Ordo Video's own colour (library menu > Settings): Amber tints the player's play button differently from "Same as
# Ordo"; put back to "Same as Ordo" afterwards. Read from a raw screenshot (RGBA after a 12- or 16-byte header).
pixel() {
    a exec-out screencap > "$tmp/screen.raw"
    python3 - "$tmp/screen.raw" "$1" "$2" <<'PY'
import os, struct, sys
data = open(sys.argv[1], "rb").read()
w, h = struct.unpack("<II", data[:8])
head = len(data) - w * h * 4
x, y = int(sys.argv[2]), int(sys.argv[3])
r, g, b = data[head + (y * w + x) * 4: head + (y * w + x) * 4 + 3]
print("%02x%02x%02x" % (r, g, b))
PY
}
# The play button's own colour: just inside its round background, left of the icon.
play_colour() {
    local l t r b
    read -r l t r b < <(ui bounds playPause | head -1) || return 1
    pixel $((l + (r - l) / 8)) $(((t + b) / 2))
}
set_video_colour() {
    a shell am start -W -n "$PKG/me.zhanghai.android.files.viewer.video.VideoLauncherActivity" >/dev/null; sleep 2; guard
    ui tap actionSettings >/dev/null; sleep 2
    ui tap "$1" >/dev/null; sleep 1.5
    back 1; back 1
}
open_paused() {
    open_folder 02-clip.mp4
    ui tap 02-clip.mp4 >/dev/null; wait_on viewer.video.VideoPlayerActivity 8; pause
}
set_video_colour "Wie Ordo|Same as Ordo"
open_paused; c_ordo=$(play_colour); back 2
set_video_colour "Bernstein|Amber"
open_paused; c_amber=$(play_colour); back 2
set_video_colour "Wie Ordo|Same as Ordo"
check "Ordo Video's own colour tints the play button ($c_ordo -> $c_amber)" \
    test -n "$c_ordo" -a -n "$c_amber" -a "$c_ordo" != "$c_amber"

# Ordo Video's settings page has its groups; the player's ⋮ › Video info names resolution and codec in plain words
# (each row is one node "label: value" for TalkBack).
a shell am start -W -n "$PKG/me.zhanghai.android.files.viewer.video.VideoLauncherActivity" >/dev/null; sleep 2; guard
ui tap actionSettings >/dev/null; sleep 2
check "Ordo Video's settings offer the end of a video" ui wait "Am Ende|At the end" 5
back 1; back 1
open_paused
ui tap "Weitere Optionen|More options" >/dev/null; sleep 1
ui tap "Videoinfos|Video info" >/dev/null; sleep 2
check "Video info shows resolution and frame rate" \
    ui wait "Auflösung: 1920 × 1080 · 30 fps|Resolution: 1920 × 1080 · 30 fps" 5
check "Video info names the codec in words" ui has "Video-Codec: H.264|Video codec: H.264"
back 1; back 2

# 9. Rotating (phone: the rotate button) keeps the position and playback.
open_folder 02-clip.mp4
ui tap 02-clip.mp4 >/dev/null; wait_on viewer.video.VideoPlayerActivity 8; pause
if ui has rotate; then
    read -r l t r b < <(ui bounds rotate | head -1)
    a shell input keyevent KEYCODE_MEDIA_PLAY; sleep 1; p9=$(pos)
    # The controls stay up 4 s after play: the button is still where it was read.
    a shell input tap $(((l + r) / 2)) $(((t + b) / 2)); sleep 3
    check "after rotating the player is still in front" on viewer.video.VideoPlayerActivity
    check "after rotating it still plays" playing
    pause; p10=$(pos)
    check "after rotating the position went on ($p9 -> $p10 ms)" test "${p10:-0}" -ge "${p9:-0}"
else
    echo "SKIP rotate (tablet: Android 16 ignores orientation requests from 600 dp)"
fi
back 2

echo "SKIP headphones unplugged / another app takes audio focus (not scriptable from adb; check by hand)"
a shell am force-stop "$PKG"
finish
