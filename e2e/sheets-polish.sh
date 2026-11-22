#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# farm: needs=shots
# Screens and checks of the 0.17 polish (#121), WRITES /storage/emulated/0/ordo-e2e-polish (removed again): Ordo Video's settings
# and the Upscaling sheet, the Klang sheet's last cards (headphones, USB DAC), Musik's queue, the stepper bounds of
# "Adjust picture". In portrait and landscape; photos only while Ordo is in front (E2E_SHOTS=<dir>). Volume stays 0.
. "$(dirname "$0")/lib.sh" "$@"
OUT=${E2E_SHOTS:-/tmp/ordo-sheets-polish}; mkdir -p "$OUT"
DIR=/storage/emulated/0/ordo-e2e-polish
ROOTSONG=/storage/emulated/0/ordo-e2e-polish-root.wav
OLDAUTO=$(a shell settings get system accelerometer_rotation | tr -d '\r')
OLDROT=$(a shell settings get system user_rotation | tr -d '\r')
tmp=$(mktemp -d)
cleanup() {
    a shell input keyevent KEYCODE_MEDIA_PAUSE; a shell am force-stop "$PKG"; a shell "rm -rf $DIR $ROOTSONG"; rm -rf "$tmp"
    a shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$ROOTSONG" >/dev/null 2>&1
    a shell settings put system user_rotation "${OLDROT:-0}"
    a shell settings put system accelerometer_rotation "${OLDAUTO:-1}"
}
trap cleanup EXIT
command -v ffmpeg >/dev/null || { echo "SKIP sheets polish (no ffmpeg on the host)"; exit 0; }
ffmpeg -v error -f lavfi -i "sine=frequency=330:duration=60" -ac 2 "$tmp/a-eins.wav"
cp "$tmp/a-eins.wav" "$tmp/b-zwei.wav"; cp "$tmp/a-eins.wav" "$tmp/c-drei.wav"
ffmpeg -v error -f lavfi -i "testsrc=size=640x360:rate=25:duration=30" -f lavfi -i "anullsrc=r=48000:cl=stereo" -t 30 \
    -c:v libx264 -pix_fmt yuv420p -c:a aac "$tmp/s.mp4"
a shell "rm -rf $DIR; mkdir -p $DIR"; a push "$tmp/." "$DIR/" >/dev/null
a push "$tmp/a-eins.wav" "$ROOTSONG" >/dev/null
for f in $DIR/a-eins.wav $DIR/b-zwei.wav $DIR/c-drei.wav $ROOTSONG; do
    a shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$f" >/dev/null 2>&1
done
wake; grant
open_dir() {
    a shell am force-stop "$PKG"
    a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
        -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null; sleep 3; guard
}
shot() { screenshot "$OUT/$1-$orient.png" && echo "SHOT $1-$orient"; }
for orient in portrait landscape; do
    a shell settings put system accelerometer_rotation 0
    a shell settings put system user_rotation $([ $orient = portrait ] && echo 0 || echo 1); sleep 2 # deliberate: the rotation animation

    # Ordo Video settings and the Upscaling sheet.
    a shell am start -W -n "$PKG/me.zhanghai.android.files.viewer.video.VideoLauncherActivity" >/dev/null; guard
    ui tap actionSettings >/dev/null; sleep 2; shot 01-video-settings # deliberate: the screenshot waits for the end of the slide-in animation
    # One more swipe: a Samsung taskbar covers the bottom row, and a tap there opens the app drawer.
    scroll_to "Hochskalieren|Upscaling" 6 >/dev/null
    read -r W H < <(screen_size); a shell input swipe $((W / 2)) $((H / 2)) $((W / 2)) $((H / 3)) 400; sleep 1.5; still_muted
    shot 02-video-settings-bild
    ui tap "Hochskalieren|Upscaling" >/dev/null; sleep 2; shot 03-hochskalieren
    back 1; back 1

    # Klang sheet: the last cards.
    open_dir; ui tap a-eins.wav >/dev/null; sleep 3; media_key PAUSE 1
    ui tap actionSound >/dev/null
    scroll_to "Unverfälscht an USB-DAC|Bit-perfect to USB DAC" 14 >/dev/null; swipe_up; shot 04-klang-ende
    for i in 1 2 3 4 5 6; do ui has "bietet virtuellen|offers virtual|Virtueller Raumklang, leise|Virtual surround, full" && break; swipe_down; done
    shot 05-klang-kopfhoerer
    back 1
    # Musik's queue (three songs in the folder).
    ui tap "Warteschlange|Queue" >/dev/null || { ui tap "Weitere Optionen|More options" >/dev/null
        ui tap "Warteschlange|Queue" >/dev/null; }
    sleep 2; shot 06-warteschlange # deliberate: the screenshot waits for the end of the slide-in animation
    back 1

    # Musik's Folders tab: a song right at the storage root.
    a shell am force-stop "$PKG"
    a shell am start -W -n "$PKG/me.zhanghai.android.files.viewer.audio.MusicLauncherActivity" >/dev/null
    wait_on viewer.audio.MusicLauncherActivity 10 || guard
    ui tap "Ordner|Folders" >/dev/null; sleep 2; shot 09-musik-ordner # deliberate: the screenshot waits for the end of the slide-in animation
    check "Musik names the storage root ($orient)" ui has "Interner Speicher|Internal storage"

    # Adjust picture: stepper bounds.
    open_dir; ui tap s.mp4 >/dev/null; sleep 3
    video_menu "Bild anpassen|Adjust picture"; shot 07-bild-anpassen
    # −/+ of "Adjust picture" are 48 dp to touch (the panel thought ~40 dp from the 34 dp visible button).
    dpi=$(a shell wm density | grep -oE '[0-9]+' | tail -1)
    target_48dp() { local x1 y1 x2 y2; read -r x1 y1 x2 y2 < <(ui bounds "$1" | head -1)
        [ -n "$y2" ] && [ $(((y2 - y1) * 160)) -ge $((48 * dpi - dpi / 2)) ] && [ $(((x2 - x1) * 160)) -ge $((48 * dpi - dpi / 2)) ]; }
    check "the picture steppers are 48 dp ($orient)" eval 'target_48dp stepMinus && target_48dp stepPlus'
    back 1
    video_menu "Signalweg|Signal path"; sleep 2; shot 08-signalweg # deliberate: the screenshot waits for the end of the slide-in animation
    scroll_to "Messwerte|Measurements" 4 >/dev/null; shot 08b-signalweg-messwerte
    back 1
done
check "media volume stays 0" test "$(media_volume)" = 0
finish
