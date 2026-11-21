#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Ordo Video's "Detail plus" and "Measurements", WRITES: creates /storage/emulated/0/ordo-e2e-detailplus (with an Anime
# subfolder) from clips made by ffmpeg on the host and removes it again; switches the debug log on and off; sets
# "Upscaling" and puts it back to Standard.
# A 640x360 clip under "Detail plus: film" runs FSRCNNX (log "detail plus FILM") and the signal path names it. The
# measurements section, unfolded, shows the timer kind, GPU time per pass (Detail plus, Lanczos, enhancement), thermal
# headroom and the Klang chain's buffers; "Share as CSV" opens the share sheet; folding stops it. After 20 s the device
# either kept Detail plus or fell back to Lanczos with the calm notice; both are reported (INFO): these numbers are
# what the test is for (network GPU time per frame, target <= 8 ms; dropped frames; headroom). "Automatic" (the default)
# names its choice for "Anime/[SubsPlease] Show - 01 (360p).mp4". Picture quality is judged by eye from the
# screenshots. Needs ffmpeg on the host.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/ordo-e2e-detailplus
TAG=OrdoVideoPicture
tmp=$(mktemp -d)
out=${OUT:-/tmp/ordo-e2e-detailplus}; mkdir -p "$out"
trap 'a shell "rm -rf $DIR"; a shell setprop log.tag.$TAG "\"\""; rm -rf "$tmp"' EXIT
command -v ffmpeg >/dev/null || { echo "SKIP detail plus (no ffmpeg on the host)"; exit 0; }

# Fine detail and text, where the network differs from Lanczos; silent sound, so the Klang chain still gets buffers.
ffmpeg -v error -f lavfi -i "testsrc2=size=640x360:rate=30:duration=40" -f lavfi -i "anullsrc=r=48000:cl=stereo" \
    -t 40 -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -b:a 96k "$tmp/01-film.mp4" \
    || echo "FAIL ffmpeg could not make 01-film.mp4"
mkdir -p "$tmp/Anime"
cp "$tmp/01-film.mp4" "$tmp/Anime/[SubsPlease] Show - 01 (360p).mp4"
a shell "rm -rf $DIR; mkdir -p $DIR"
a push "$tmp/." "$DIR/" >/dev/null

wake; grant; a logcat -b crash -c
a shell am force-stop "$PKG"
a shell setprop log.tag.$TAG DEBUG
log() { a logcat -d -s "$TAG:D" | tr -d '\r'; }
open_file() {
    a shell am start -W -a android.intent.action.VIEW -d "file://$1" -t inode/directory \
        -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
    sleep 3; guard; ui wait "$2" 10 >/dev/null; ui tap "$2" >/dev/null
    wait_on viewer.video.VideoPlayerActivity 8
}
menu() { video_menu "$1"; }
# ui has/wait compare whole texts (no regex): content checks grep the screen's texts instead.
seen() { local end=$((SECONDS + ${2:-0})); until ui texts | grep -E -- "$1" >/dev/null; do [ $SECONDS -ge $end ] && return 1; sleep 0.5; done; }
upscaling() {
    a shell am start -W -n "$PKG/me.zhanghai.android.files.viewer.video.VideoLauncherActivity" >/dev/null; sleep 2; guard
    ui tap actionSettings >/dev/null; sleep 2
    scroll_to "Hochskalieren|Upscaling" >/dev/null # mid-list: below the fold on the Tab (20.11.2026)
    ui tap "Hochskalieren|Upscaling" >/dev/null; sleep 1.5
    ui tap "$1" >/dev/null; sleep 1.5
    back 1; back 1
}

# 1. The setting exists and offers Standard. Its value persists: a run stopped early left "Automatic" (Tab, 20.11.2026).
upscaling "Standard (Lanczos)"
a shell am start -W -n "$PKG/me.zhanghai.android.files.viewer.video.VideoLauncherActivity" >/dev/null; sleep 2; guard
ui tap actionSettings >/dev/null; sleep 2
scroll_to "Hochskalieren|Upscaling" >/dev/null
# A short pull, so the summary under the title is on screen too (settings list, no gesture there).
read -r W H < <(screen_size); a shell input swipe $((W / 2)) $((H * 6 / 10)) $((W / 2)) $((H * 4 / 10)) 400; sleep 1
check "the settings have Upscaling, set to Standard (Lanczos)" ui summary "Hochskalieren|Upscaling" "Standard (Lanczos)"
back 1; back 1

# 2. Detail plus: film on a 360p clip.
upscaling "Detail plus: Film|Detail plus: film"
a logcat -c
open_file "$DIR" 01-film.mp4
sleep 6
check "the film network is set as an effect" eval 'log | grep "effects: detail plus FILM"'
menu "Signalweg|Signal path"
check "the signal path names Detail plus film" seen "Detail plus (Film|film) \(FSRCNNX x2\)" 5
screenshot "$out/signal-detailplus.png"

# 3. Measurements: unfold, measure 20 s, read the rows, share the CSV, fold.
# Swipe only inside the open sheet: on the bare picture a vertical swipe is the volume gesture (Tab went to 15).
for i in 1 2; do ui has "Signalweg|Signal path" && swipe_up; done # whole texts: the sheet's title
ui tap "Messwerte|Measurements" >/dev/null; sleep 2
check "unfolding starts measuring" seen "Misst\. Zum Beenden antippen\.|Measuring\. Tap to stop\." 5
check "every pass is wrapped while measuring" eval 'log | grep "measuring true"'
# Landscape tablets (A8, W90) show the unfolded rows and the CSV button only below the fold; a dump lists only what shows.
ui has "Als CSV teilen|Share as CSV" || swipe_up
sleep 20
check "the timer kind is shown" seen "GPU-Timer|GPU timer|CPU-Uhr|CPU clock"
if seen "Detail plus: [0-9]"; then echo "INFO Detail plus kept for 20 s"; else echo "INFO Detail plus not timed (fell back?)"; fi
# Dropped frames switch the whole enhancement off, Lanczos with it, and the row says why (Tab S9, 21.11.2026: 55
# dropped while the screen was read, 0.9 ms GPU per frame): then nothing is left to time.
check "Lanczos is timed, or the enhancement says why it went off" \
    seen "Lanczos: [0-9]|Gerät kam nicht hinterher|device couldn.t keep up"
check "the Klang chain's buffers are measured" seen "Puffer in der letzten Sekunde|buffers in the last second"
screenshot "$out/measurements.png"
ui texts | grep -E " ms · |1,00|1\.00|Unterläufe|underruns" | sed 's/^/INFO /'
a logcat -d -s "$TAG:I" | tr -d '\r' | grep "Detail plus .* off" | tail -1 | sed 's/^/INFO fell back: /'
ui tap "Als CSV teilen|Share as CSV" >/dev/null; sleep 2
check "Share as CSV opens the share sheet" eval 'top | grep -qiE "chooser|resolver"'
back 1
ui has "Messwerte|Measurements" || swipe_down
ui tap "Messwerte|Measurements" >/dev/null; sleep 1.5
check "folding stops measuring" seen "Aus\. Zum Messen antippen\.|Off\. Tap to start measuring\." 5
back 1; back 2

# 4. Automatic: an anime-looking name gets the cartoon network (or the film network or Lanczos where too slow).
upscaling "Automatisch|Automatic"
a logcat -c
open_file "$DIR/Anime" "[SubsPlease] Show - 01 (360p).mp4"
sleep 6
# The cartoon network, the film network or Lanczos when this device measured it too slow before (all name the reason).
check "Automatic decides for an anime name and logs why" eval 'log | grep -E "auto: (CARTOON ANIME|FILM ANIME_FILM|lanczos TOO_SLOW)"'
menu "Signalweg|Signal path"
check "the signal path says why Automatic chose" seen "Automatisch: |Automatic: " 5
screenshot "$out/signal-auto.png"
back 1; back 2

upscaling "Standard (Lanczos)"
finish
