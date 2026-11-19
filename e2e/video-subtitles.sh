#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Video subtitles (phase 2), WRITES: creates /storage/emulated/0/ordo-e2e-subs with files made by ffmpeg on the host
# and removes it again: film.mp4 with film.de.srt (Windows-1252, "Größe, Übermaß") and film.en.srt (UTF-8) next to it,
# intern.mkv with an English SRT track inside. The sheet (toolbar "Subtitles") lists Off, both files with their language
# and "Load subtitles…"; the German file (on by itself on a German device, sound language unknown) reads as
# Windows-1252; two taps on +1 s show "+2 s", still there after the player was closed; the MKV's track shows as
# "English · SRT" and, chosen, says the delay is for files only; Ordo Video's settings have the "Subtitles" group.
# Whether the text is drawn right (the SubtitleView paints on a canvas, not in the UI tree) is saved as a screenshot
# for a look by hand: SubtitleView text is not in uiautomator's tree. Needs ffmpeg on the host.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/ordo-e2e-subs
tmp=$(mktemp -d); trap 'a shell "rm -rf $DIR"; rm -rf "$tmp"' EXIT
command -v ffmpeg >/dev/null || { echo "SKIP video subtitles (no ffmpeg on the host)"; exit 0; }

ffmpeg -v error -f lavfi -i "testsrc=size=1280x720:rate=30:duration=30" -f lavfi -i "sine=frequency=440:duration=30" \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -shortest "$tmp/film.mp4" || { fail "ffmpeg film.mp4"; finish; }
printf '1\r\n00:00:00,500 --> 00:00:29,000\r\nGröße, Übermaß\r\n' | iconv -f UTF-8 -t WINDOWS-1252 > "$tmp/film.de.srt"
printf '1\n00:00:00,500 --> 00:00:29,000\nHello there\n' > "$tmp/film.en.srt"
printf '1\n00:00:00,500 --> 00:00:29,000\nInside the film\n' > "$tmp/intern.srt"
ffmpeg -v error -i "$tmp/film.mp4" -i "$tmp/intern.srt" -map 0 -map 1 -c copy -c:s srt \
    -metadata:s:s:0 language=eng "$tmp/intern.mkv" || { fail "ffmpeg intern.mkv"; finish; }
rm -f "$tmp/intern.srt"
a shell "rm -rf $DIR; mkdir -p $DIR"
a push "$tmp/." "$DIR/" >/dev/null

wake; grant; a logcat -b crash -c
a shell am force-stop "$PKG"
pause() { a shell input keyevent KEYCODE_MEDIA_PAUSE >/dev/null; sleep 1.5; }
open_video() {
    a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
        -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
    sleep 3; guard; ui wait "$1" 10 >/dev/null
    ui tap "$1" >/dev/null; wait_on viewer.video.VideoPlayerActivity 8; sleep 2; pause
}
sheet() { ui tap "Untertitel|Subtitles" >/dev/null; sleep 1.5; }

# 1. Files next to the film, the German one on by itself and read as Windows-1252.
open_video film.mp4
a shell screencap -p /sdcard/ordo-e2e-subs.png && a pull /sdcard/ordo-e2e-subs.png "${TMPDIR:-/tmp}/ordo-e2e-subs.png" \
    >/dev/null && a shell rm /sdcard/ordo-e2e-subs.png
echo "INFO screenshot with the German line: ${TMPDIR:-/tmp}/ordo-e2e-subs.png (look for \"Größe, Übermaß\")"
sheet
check "the sheet offers Off" ui has "Aus|Off"
check "the sheet lists the German file with its language" ui has "Deutsch · film.de.srt|German · film.de.srt"
check "the sheet lists the English file" ui has "Englisch · film.en.srt|English · film.en.srt"
check "the sheet offers Load subtitles…" ui has "Untertitel laden…|Load subtitles…"
# On a German device it came on by itself; on any device a tap chooses it (the label is read when the sheet opens).
ui tap "Deutsch · film.de.srt|German · film.de.srt" >/dev/null; sleep 1; back 1; sheet
check "the German file is read as Windows-1252" ui has "Automatisch (windows-1252)|Automatic (windows-1252)"

# 2. Delay in steps, remembered per film.
ui tap "+1 s" >/dev/null; sleep 0.5; ui tap "+1 s" >/dev/null; sleep 0.5
check "two taps on +1 s show +2 s as the delay" ui has "+2 s"
back 1; back 2
open_video film.mp4
sheet
check "the delay is remembered for the film" ui has "+2 s"
ui tap "−1 s" >/dev/null; sleep 0.5; ui tap "−1 s" >/dev/null; sleep 0.5
check "the size chips are there" ui has "Sehr groß|Extra large"
back 1; back 2

# 3. A track inside the MKV.
open_video intern.mkv
sheet
check "the MKV's track shows with its language and kind" ui has "Englisch · SRT|English · SRT"
ui tap "Englisch · SRT|English · SRT" >/dev/null; sleep 1
check "for a track the sheet says the delay is for files" ui has \
    "Die Verzögerung gilt für Untertitel-Dateien. Spuren im Video behalten ihr eigenes Timing.|Delay works for subtitle files. Tracks inside the video keep their own timing."
back 1; back 2

# 4. Settings: the "Subtitles" group.
a shell am start -W -n "$PKG/me.zhanghai.android.files.viewer.video.VideoLauncherActivity" >/dev/null; sleep 2; guard
ui tap actionSettings >/dev/null; sleep 2
for i in 1 2 3; do ui has "Untertitel automatisch an|Subtitles automatically" && break; swipe_up; done
check "Ordo Video's settings have \"Subtitles automatically\"" ui has "Untertitel automatisch an|Subtitles automatically"
check "Ordo Video's settings have the preferred subtitle language" \
    ui has "Bevorzugte Untertitel-Sprache|Preferred subtitle language"
back 1; back 1

check "no crash" test "$(crashes)" = 0
a shell am force-stop "$PKG"
finish
