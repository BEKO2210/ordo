#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Raw screenshots for the Play listing: capture.sh <serial> <en|de> <phone|tablet|seven> <out dir>
# Uses the e2e helpers, so a picture is only taken while Ordo is in front and unlocked, and media stays at volume 0.
# Run demo_media.py first and push its Music/OrdoDemo and Movies/OrdoDemo to the device: only demo media is shown.
# phone: the window is set to 1080x2280 at 420 dpi (411 dp wide), so a tablet renders Ordo's real phone layout;
# seven: 600 dp wide like a 7-inch tablet; tablet: the device as it is. Size, density, rotation, night mode and the
# app language are put back at the end.
# PARTS (default "music video files") picks what to capture. LIBRARY=0 skips the music library, for a device that
# also holds its owner's music: the player then opens from the demo folder.
. "$(dirname "$0")/../../e2e/lib.sh" "$1"
LANG_=$2; MODE=$3; OUT=$4; mkdir -p "$OUT"
P=$([ "$MODE" = phone ] && echo "" || echo "$MODE-")
OLDROT=$(a shell settings get system user_rotation | tr -d '\r'); OLDAUTO=$(a shell settings get system accelerometer_rotation | tr -d '\r')
OLDNIGHT=$(a shell cmd uimode night | grep -o 'yes\|no\|auto' | head -1)
restore() {
    a shell am force-stop "$PKG"
    a shell wm size reset; a shell wm density reset
    a shell settings put system user_rotation "${OLDROT:-0}"; a shell settings put system accelerometer_rotation "${OLDAUTO:-1}"
    a shell cmd uimode night "${OLDNIGHT:-auto}" >/dev/null
    a shell cmd locale set-app-locales "$PKG" --locales "''" >/dev/null 2>&1
}
trap restore EXIT
rotate() { a shell settings put system accelerometer_rotation 0; a shell settings put system user_rotation "$1"; sleep 2.5; }
shot() { screenshot "$OUT/$P$1.png" >/dev/null && echo "shot $P$1"; }

case $MODE in
    phone) a shell wm size 1080x2280; a shell wm density 420 ;;
    seven) a shell wm density "$(( $(a shell wm size | tail -1 | sed 's/.*: //; s/x.*//') * 160 / 600 ))" ;;
esac
a shell cmd uimode night yes >/dev/null
a shell cmd locale set-app-locales "$PKG" --locales "$([ "$LANG_" = de ] && echo de-DE || echo en-US)"
BASE=$([ "$MODE" = tablet ] && echo 1 || echo 0)
rotate $BASE; wake
a shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null

PARTS=${PARTS:-music video files}
part() { [[ " $PARTS " == *" $1 "* ]]; }
folder() {
    a shell am start -W -a android.intent.action.VIEW -d "file:///storage/emulated/0/$1" -t inode/directory \
        -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null; sleep 3; guard
}

# Ordo Music: library, player, Sound, lyrics
if part music; then
a shell am force-stop "$PKG"
if [ "${LIBRARY:-1}" = 1 ]; then
    a shell am start -n "$PKG/me.zhanghai.android.files.viewer.audio.MusicLauncherActivity" >/dev/null; sleep 4; guard
    ui tap "Alben|Albums" >/dev/null; sleep 4; shot library
    ui tap "Titel|Songs" >/dev/null; sleep 2
    ui tap "City Lights" >/dev/null
else
    folder Music/OrdoDemo; scroll_to "Nova Reed - City Lights.mp3" 4 >/dev/null; ui tap "Nova Reed - City Lights.mp3" >/dev/null
fi
wait_on viewer.audio.AudioPlayerActivity 8; sleep 3
a shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 2; shot music
ui tap actionSound >/dev/null; sleep 3; ui tap equalizerEnabled >/dev/null; sleep 1; ui tap "Bass" >/dev/null; sleep 2; shot sound
ui tap "Neutral|Flat" >/dev/null; sleep 1; ui tap equalizerEnabled >/dev/null; sleep 1; back 2 # the device's sound as before
ui tap lyricsChip >/dev/null; sleep 3; shot lyrics; back 2
fi


# Ordo Video: a film with subtitles in the shot's language, the "More" sheet, the signal path of an HDR film.
# On a phone the player turns to landscape by itself for a wide film. While a film plays, a screen read sees only the
# picture (Tab S9, 21.11.2026), so every step pauses first; paused, the bar stays and the read sees its buttons.
center() { read -r W H < <(screen_size); a shell input tap $((W / 2)) $((H / 2)); }
playing() { a shell dumpsys media_session | grep -q 'state=PLAYING'; }
pause_film() {
    a shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1.2
    playing && { center; sleep 0.7; center; sleep 1.2; } # bar in, then its play/pause button in the middle
    rm -f "$DUMP"; ui has playPause || { center; sleep 1.2; }
}
# Ordo remembers where a film was left (by path), so each run plays fresh copies in a folder of its own.
FILMS=Movies/OrdoStore-$(date +%s)
open_film() {
    a shell am force-stop "$PKG"
    a shell am start -W -a android.intent.action.VIEW -d "file:///storage/emulated/0/$FILMS" -t inode/directory \
        -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
    sleep 3; guard
    scroll_to "$1" 3 >/dev/null; ui tap "$1" >/dev/null; wait_on viewer.video.VideoPlayerActivity 8; sleep 1
}
SUB=$([ "$LANG_" = de ] && echo "Deutsch · Tears of Steel.de.srt" || echo "English · Tears of Steel.en.srt")
if part video; then
a shell "cp -r /sdcard/Movies/OrdoDemo /sdcard/$FILMS"
rotate 1 # with rotation locked the phone player stays upright, so the film is shown the way people watch it
open_film "Tears of Steel.mp4"
pause_film
ui tap actionSubtitles >/dev/null || echo "not found: actionSubtitles"; sleep 2
ui tap "$SUB" >/dev/null || echo "not found: $SUB"; sleep 1
rm -f "$DUMP"; ui has sheetTitle && back 1
rm -f "$DUMP"; ui tap playPause >/dev/null || a shell input keyevent KEYCODE_MEDIA_PLAY
sleep 8 # the line "Why don't you just admit…" is on screen from 10.8 s to 14 s
# Paused without the bar: the picture and its subtitle line. The tap lands beside the play button, on the picture.
aside() { read -r W H < <(screen_size); a shell input tap $((W * 3 / 5)) $((H * 3 / 5)); }
pause_film; aside; sleep 1.5; shot video
aside; sleep 1.2; rm -f "$DUMP"; ui tap actionMore >/dev/null || echo "not found: actionMore"; sleep 2.5; shot more; back 1.5
open_film "Tears of Steel (HDR10).mp4"
sleep 3; pause_film
ui tap actionMore >/dev/null || echo "not found: actionMore"; sleep 2; scroll_to "Signalweg|Signal path" 3 >/dev/null
ui tap "Signalweg|Signal path" >/dev/null || echo "not found: Signal path"; sleep 2
read -r W H < <(screen_size); a shell input swipe $((W / 2)) $((H * 8 / 10)) $((W / 2)) $((H * 5 / 10)) 600; sleep 1.5
shot signal; back 1.5
a shell am force-stop "$PKG"
a shell "rm -r /sdcard/$FILMS" # only the copies made above
rotate $BASE
fi


# Files: the demo music folder and the storage analysis
part files || exit 0
folder Music/OrdoDemo; sleep 1; shot files
launch; swipe_up; ui tap analyzeButton >/dev/null; sleep 1; guard; ui wait usageHero 120 >/dev/null; sleep 3; shot storage
