#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Video subtitles (phase 2), WRITES: creates /storage/emulated/0/ordo-e2e-subs with files made by ffmpeg on the host
# and removes it again: film.mp4 with film.de.srt (Windows-1252, "Größe, Übermaß") and film.en.srt (UTF-8) next to it,
# intern.mkv with an English SRT track inside. The sheet (toolbar "Subtitles") lists Off, both files with their language
# and "Load subtitles…"; the German file (on by itself on a German device, sound language unknown) reads as
# Windows-1252; two taps on +1 s show "+2 s", still there after the player was closed; the MKV's track shows as
# "English · SRT" and, chosen, takes the delay steps like a file; Ordo Video's settings have the "Subtitles" group.
# Whether the text is drawn right (the SubtitleView paints on a canvas, not in the UI tree) is saved as a screenshot
# for a look by hand: SubtitleView text is not in uiautomator's tree. Needs ffmpeg on the host.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/ordo-e2e-subs
tmp=$(mktemp -d); trap 'a shell "rm -rf $DIR"; rm -rf "$tmp"' EXIT
command -v ffmpeg >/dev/null || { echo "SKIP video subtitles (no ffmpeg on the host)"; exit 0; }

ffmpeg -v error -f lavfi -i "testsrc=size=1280x720:rate=30:duration=30" -f lavfi -i "sine=frequency=440:duration=30" \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -shortest "$tmp/film.mp4" || { fail "ffmpeg film.mp4"; finish; }
printf '1\r\n00:00:00,500 --> 00:00:29,000\r\nGröße, Übermaß\r\n' | iconv -f UTF-8 -t WINDOWS-1252 > "$tmp/film.de.srt"
# The owner's case (Tab S9, 20.11.2026): UTF-8 without BOM read as "fÃ¼r" after a charset chip had been pressed.
printf '1\n00:00:00,500 --> 00:00:29,000\nUntertitel für den Crawler\n' > "$tmp/film.en.srt"
printf '1\n00:00:00,500 --> 00:00:29,000\nInside the film\n' > "$tmp/intern.srt"
ffmpeg -v error -i "$tmp/film.mp4" -i "$tmp/intern.srt" -map 0 -map 1 -c copy -c:s srt \
    -metadata:s:s:0 language=eng "$tmp/intern.mkv" || { fail "ffmpeg intern.mkv"; finish; }
rm -f "$tmp/intern.srt"
a shell "rm -rf $DIR; mkdir -p $DIR"
a push "$tmp/." "$DIR/" >/dev/null

wake; grant; a logcat -b crash -c
a shell am force-stop "$PKG"
pause() { media_key PAUSE 1.5; }
open_video() {
    a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
        -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
    guard; ui wait "$1" 10 >/dev/null
    ui tap "$1" >/dev/null; wait_on viewer.video.VideoPlayerActivity 8; media_wait PLAY 2; pause
}
sheet() { ui tap "Untertitel|Subtitles" >/dev/null; sleep 1.5; }

# 1. Files next to the film, the German one on by itself and read as Windows-1252.
open_video film.mp4
screenshot "${TMPDIR:-/tmp}/ordo-e2e-subs.png" &&
    echo "INFO screenshot with the German line: ${TMPDIR:-/tmp}/ordo-e2e-subs.png (look for \"Größe, Übermaß\")"
sheet
check "the sheet offers Off" ui has "Aus|Off"
check "the sheet lists the German file with its language" ui has "Deutsch · film.de.srt|German · film.de.srt"
check "the sheet lists the English file" ui has "Englisch · film.en.srt|English · film.en.srt"
check "the sheet offers Load subtitles…" ui has "Untertitel laden…|Load subtitles…"
# On a German device it came on by itself; on any device a tap chooses it (the label is read when the sheet opens).
ui tap "Deutsch · film.de.srt|German · film.de.srt" >/dev/null; sleep 1; back 1; sheet
# On a landscape phone the charsets start below the window; the sheet opens again at the top for the file rows.
scroll_to "Automatisch (windows-1252)|Automatic (windows-1252)" 4
check "the German file is read as Windows-1252" ui has "Automatisch (windows-1252)|Automatic (windows-1252)"
back 1; sheet
# 1b. A manual charset belongs to its own file and never garbles UTF-8: Windows-1252 pressed on the UTF-8 file
# bounces back to "Automatic (UTF-8)", and the picture is kept for a look at "für".
ui tap "Englisch · film.en.srt|English · film.en.srt" >/dev/null
scroll_to "Westeuropäisch (Windows-1252)|Western (Windows-1252)" 3
ui tap "Westeuropäisch (Windows-1252)|Western (Windows-1252)" >/dev/null; sleep 1.5; back 1; sheet
scroll_to "Automatisch (UTF-8)|Automatic (UTF-8)" 3
check "a manual code page never garbles a UTF-8 file" ui has "Automatisch (UTF-8)|Automatic (UTF-8)"
back 1
screenshot "${TMPDIR:-/tmp}/ordo-e2e-subs-utf8.png" &&
    echo "INFO screenshot with the UTF-8 line: ${TMPDIR:-/tmp}/ordo-e2e-subs-utf8.png (look for \"für\", never \"fÃ¼r\")"
sheet; ui tap "Deutsch · film.de.srt|German · film.de.srt" >/dev/null; sleep 1; back 1; sheet

# 2. Delay in steps, remembered per film. The film's delay outlives the test folder (it is kept per path), so a
# run starts wherever the last one left it: the value is read (the step buttons carry a description, it does not).
# The buttons are tapped by that description: at +1 s the value reads "+1 s" too and came first in the screen read.
later="1 s später zeigen|Show 1 s later"; earlier="1 s früher zeigen|Show 1 s earlier"
delay() {
    ui texts | awk -F'|' '$2 == "" && $3 == "" && $1 ~ /^(\+|−)?[0-9]+([.,][0-9])? s$/ {
        v = $1; neg = (index(v, "−") == 1); gsub(/[^0-9.,]/, "", v); sub(/,/, ".", v); printf "%d\n", (neg ? -v : v) * 1000; exit }'
}
scroll_to "$later" 4 # below the file rows on a landscape phone
d0=$(delay)
ui tap "$later" >/dev/null; ui tap "$later" >/dev/null; sleep 0.5
d2=$(delay)
check "two taps on +1 s add 2 s to the delay (${d0:-?} -> ${d2:-?} ms)" test -n "$d0" -a "${d2:-x}" = "$((${d0:-0} + 2000))"
back 1; back 2
open_video film.mp4
sheet
scroll_to "$later" 4
check "the delay is remembered for the film" test "$(delay)" = "$d2"
ui tap "$earlier" >/dev/null; ui tap "$earlier" >/dev/null
# The style switches come first (SubtitleSheet: preview, Like the system, Styles of the file, then size, font, edge):
# scroll_to only moves down the sheet, so they are read before the size.
scroll_to "Wie System|Like the system" 8
check "the style can follow Android's caption settings" ui has "Wie System|Like the system"
scroll_to "Stil der Datei|Styles of the file" 3
check "ASS files may keep their own styles" ui has "Stil der Datei|Styles of the file"
# The size chips lie below the charsets and the preview: on a phone the sheet scrolls (landscape: one swipe moves
# about half a window, the preview alone is taller than that).
scroll_to "Größer|Larger" 8
check "the size has its − and + steps" ui has "Größer|Larger"
# One step larger shows 110 % and is kept; one step smaller puts it back.
was=$(ui texts | awk -F'|' '$1 ~ /^[0-9]+[^0-9]*%$/ { print $1; exit }')
ui tap "Größer|Larger" >/dev/null
now=$(ui texts | awk -F'|' '$1 ~ /^[0-9]+[^0-9]*%$/ { print $1; exit }')
check "a step changes the size (${was:-?} -> ${now:-?})" test -n "$was" -a "$was" != "$now"
ui tap "Kleiner|Smaller" >/dev/null
scroll_to "Kontur|Outline" 3
check "the edge offers outline, shadow and box" ui has "Kasten|Box"
back 1; back 2

# 3. A track inside the MKV.
open_video intern.mkv
sheet
check "the MKV's track shows with its language and kind" ui has "Englisch · SRT|English · SRT"
ui tap "Englisch · SRT|English · SRT" >/dev/null
# A text track inside the film now has the delay steps too (Ordo draws its cues, EmbeddedCueQueue).
scroll_to "$later" 4 # below the track rows on a landscape phone
ui tap "+1 s" >/dev/null
check "a text track inside the film takes the delay" ui see "+1 s"
check "and has no picture-track note" eval '! ui has "Verschieben nur für Text-Untertitel.|Shifting works for text subtitles only."'
ui tap "−1 s" >/dev/null; sleep 0.5 # deliberate: the choice is taken over before the next step leaves the screen
back 1; back 2

# 4. Settings: the "Subtitles" group.
a shell am start -W -n "$PKG/me.zhanghai.android.files.viewer.video.VideoLauncherActivity" >/dev/null; guard
ui tap actionSettings >/dev/null
# Far down the list: in landscape 8 swipes only reached the gestures (A15, qa8).
scroll_to "Untertitel automatisch an|Subtitles automatically" 20
check "Ordo Video's settings have \"Subtitles automatically\"" ui has "Untertitel automatisch an|Subtitles automatically"
check "Ordo Video's settings have the preferred subtitle language" \
    ui has "Bevorzugte Untertitel-Sprache|Preferred subtitle language"
back 1; back 1

check "no crash" test "$(crashes)" = 0
a shell am force-stop "$PKG"
finish
