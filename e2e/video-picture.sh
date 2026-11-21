#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# farm: needs=shots,hdr
# Ordo Video's picture, WRITES: creates /storage/emulated/0/ordo-e2e-picture with clips made by ffmpeg on the host and
# removes it again; switches the debug log on (setprop log.tag.OrdoVideoPicture DEBUG) and off.
# A 640x360 24p SDR clip (a slow gradient, where banding shows) gets the enhancement decided before prepare (no
# second prepare, no ENGAGE in the log), frames are
# processed and their GPU time is printed (mean and maximum per 120 frames, to compare devices); ⋮ › Signal path names
# the decoder, "Lanczos 3" and the sound; ⋮ › Picture enhancement switches it off (CLEAR) and on again (APPLY) with a
# hint; a clip exactly the screen's size is left alone (NOT_SMALLER); with "Match frame rate: Always" Ordo asks for
# 24 fps itself (API 31+), "Only when seamless" is put back; an HDR10 clip (if ffmpeg has libx265) stays plain on an HDR screen and gets Ordo's HDR to SDR pass on an SDR screen; Video
# info links to the signal path; the settings page has the "Picture" group. Whether the picture LOOKS better, and how
# long the screen blinks under "Always", is judged by eye: screenshots are saved for that. Needs ffmpeg on the host.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/ordo-e2e-picture
TAG=OrdoVideoPicture
tmp=$(mktemp -d)
out=${OUT:-/tmp/ordo-e2e-picture}; mkdir -p "$out"
trap '[ -n "${LOGPID:-}" ] && kill $LOGPID; a shell "rm -rf $DIR"; a shell setprop log.tag.$TAG "\"\""; rm -rf "$tmp"' EXIT
command -v ffmpeg >/dev/null || { echo "SKIP video picture (no ffmpeg on the host)"; exit 0; }

read -r W H < <(screen_size)
long=$(( (W > H ? W : H) / 2 * 2 )); short=$(( (W > H ? H : W) / 2 * 2 ))
# A slow dark gradient in 8 bit bands visibly on every screen; the moving box gives the sharpening edges.
ffmpeg -v error -f lavfi -i "gradients=size=640x360:rate=24:duration=20:c0=0x101820:c1=0x283848:speed=0.002" \
    -f lavfi -i "sine=frequency=440:duration=20" -vf "drawbox=x=t*20:y=150:w=60:h=60:color=white@0.8:t=fill" \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -b:a 128k -shortest "$tmp/01-small.mp4" \
    || echo "FAIL ffmpeg could not make 01-small.mp4"
ffmpeg -v error -f lavfi -i "testsrc2=size=${long}x${short}:rate=30:duration=8" -f lavfi -i "sine=frequency=550:duration=8" \
    -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -shortest "$tmp/02-screen.mp4" \
    || echo "FAIL ffmpeg could not make 02-screen.mp4"
hdr=0
if ffmpeg -v error -hide_banner -encoders 2>/dev/null | grep -q libx265; then
    ffmpeg -v error -f lavfi -i "testsrc2=size=1280x720:rate=30:duration=8" -c:v libx265 -preset ultrafast \
        -pix_fmt yuv420p10le -x265-params "colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:log-level=error" \
        -tag:v hvc1 "$tmp/03-hdr.mp4" && hdr=1
fi
a shell "rm -rf $DIR; mkdir -p $DIR"
a push "$tmp/." "$DIR/" >/dev/null

wake; grant; a logcat -b crash -c
a shell am force-stop "$PKG"
a shell setprop log.tag.$TAG DEBUG
# The log is streamed into a file from each restart on: every frame logs a line, and on the S21 a later
# `logcat -d` no longer held the start of playback.
relog() {
    [ -n "${LOGPID:-}" ] && kill "$LOGPID" 2>/dev/null
    a logcat -c
    "$ADB" -s "$SERIAL" logcat -s "$TAG:D" > "$tmp/log.txt" 2>/dev/null < /dev/null & LOGPID=$!
}
log() { tr -d '\r' < "$tmp/log.txt"; }
open_folder() {
    a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
        -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
    guard; ui wait "$1" 10 >/dev/null
}
menu() { video_menu "$1"; }
# part REGEX: a text or description on screen contains it (the signal path rows say "Decoder: Hardware · c2…").
part() { ui texts | grep -E -- "$1" >/dev/null; }
settings_pick() {
    a shell am start -W -n "$PKG/me.zhanghai.android.files.viewer.video.VideoLauncherActivity" >/dev/null; guard
    ui tap actionSettings >/dev/null
    scroll_to "$1"; ui tap "$1" >/dev/null
    ui tap "$2" >/dev/null; sleep 1.5
    back 1; back 1
}

# 1. The settings page has the Picture group with both rows and their defaults.
a shell am start -W -n "$PKG/me.zhanghai.android.files.viewer.video.VideoLauncherActivity" >/dev/null; guard
ui tap actionSettings >/dev/null
check "the settings have Picture enhancement, balanced by default" scroll_to "Ausgewogen|Balanced"
# Its row can sit below the fold (Tab A8, 21.11.2026).
check "and Match frame rate, only when seamless by default" scroll_to "Nur nahtlos|Only when seamless" 3
back 1; back 1

# 2. A small SDR clip: the pipeline engages and frames are processed; their GPU time goes to the report.
relog
open_folder 01-small.mp4
ui tap 01-small.mp4 >/dev/null
check "the small clip opens Ordo's player" wait_on viewer.video.VideoPlayerActivity 8
# The start, read early: every frame logs a line, and on the S21 the first lines had left the buffer after 8 s.
sleep 2; start=$(log)
sleep 6
check "a 360p clip starts with the pipeline, decided before prepare" eval 'grep -q "prepare: pipeline true" <<<"$start"'
check "and is not prepared a second time" eval '! log | grep -- "-> ENGAGE"'
check "the enhancement is applied" eval 'grep -q -- "-> APPLY" <<<"$start"'
check "frames go through the enhancement pass" eval 'grep -qE "frame [0-9]+ [0-9]+x[0-9]+ [0-9.]+ ms" <<<"$start"'
log | grep "last 120 frames" | tail -3 | sed 's/^/INFO GPU time /'
log | grep "dropped" | tail -2 | sed 's/^/INFO /'
media_key PAUSE 1
screenshot "$out/small-enhanced.png"

# 3. The signal path: decoder, Lanczos, sound.
menu "Signalweg|Signal path"
check "Signal path opens" ui wait "Signalweg|Signal path" 5
check "it names the decoder as hardware or software" part "Decoder: (Hardware|Software)"
# A low-end device that drops frames switches the enhancement off and says why (Tab A8, 21.11.2026: 50 dropped).
check "it shows the Lanczos upscale, or why the device switched it off" \
    part "Lanczos 3|Gerät kam nicht hinterher|device couldn.t keep up"
sound_row() { local i; for i in 1 2 3; do part "(Ausgabe|Output): " && return 0; swipe_up; done; return 1; }
check "it has the sound section with the output" sound_row
screenshot "$out/signal-path.png"
back 1

# 4. The ⋮ toggle compares: off clears the effects, on applies them again.
relog
menu "Bildverbesserung|Picture enhancement"
screenshot "$out/small-plain.png"
# The hint pill fades after about a second, shorter than one screen read (2-3 s): look at the screenshot instead.
echo "SKIP the off hint (fades before a screen read; see $out/small-plain.png)"
check "and clears the effects" eval 'log | grep -- "-> CLEAR"'
menu "Bildverbesserung|Picture enhancement"
check "switched on again it applies them" eval 'log | grep -- "-> APPLY"'
back 2

# 5. A clip as large as the screen gets nothing.
relog
open_folder 02-screen.mp4
ui tap 02-screen.mp4 >/dev/null; wait_on viewer.video.VideoPlayerActivity 8; sleep 4
check "a clip as large as the screen is left alone (${long}x$short)" eval 'log | grep "NOT_SMALLER"'
back 2

# 6. "Always" asks for 24 fps itself from API 31; "Only when seamless" leaves it to Media3.
sdk=$(a shell getprop ro.build.version.sdk | tr -d '\r')
settings_pick "Bildrate anpassen|Match frame rate" "Immer (Bildschirm kann kurz schwarz werden)|Always (the screen may go dark for a moment)"
relog
open_folder 01-small.mp4
ui tap 01-small.mp4 >/dev/null; wait_on viewer.video.VideoPlayerActivity 8; sleep 4
if [ "${sdk:-0}" -ge 31 ]; then
    check "under Always Ordo asks the screen for 24 fps" eval 'log | grep "setFrameRate 24.0 ALWAYS"'
    a shell dumpsys display | grep -m2 -oE "renderFrameRate [0-9.]+|mActiveRenderFrameRate=[0-9.]+|refreshRate=[0-9.]+" \
        | sed 's/^/INFO display /'
else
    echo "SKIP frame rate Always (API $sdk < 31: only seamless exists)"
fi
# Frame pacing, twice: Media3's scheduled release times (the log, every 240 frames; the Signal path row) and what the
# panel really showed (SurfaceFlinger's present times of the video layer, last 127 frames).
sleep 10
log | grep "pacing fps" | tail -2 | sed 's/^/INFO /'
layer=$(a shell dumpsys SurfaceFlinger --list | tr -d '\r' | grep -m1 "SurfaceView.*VideoPlayerActivity")
if [ -n "$layer" ]; then
    a shell dumpsys SurfaceFlinger --latency "\"$layer\"" | tr -d '\r' | awk '
        NR == 1 { period = $1; next }
        $2 > 0 && $2 < 9e18 { if (last) { n = int(($2 - last) / period + 0.5); c[n]++; total++ } last = $2 }
        END { if (total) for (k in c) printf "INFO SurfaceFlinger: %d frames held %d vsyncs (%.1f %%)\n", c[k], k, 100 * c[k] / total }'
else
    echo "INFO SurfaceFlinger layer of the player not found"
fi
menu "Signalweg|Signal path"
# Opening the sheet pauses (video_menu) and plays on; the pacing is measured again over 240 frames (10 s at 24 fps).
# In landscape the row sits below the sheet's fold: scroll the sheet (only while it is open, never the picture).
pacing_shown() { local i; for i in 1 2 3 4 5 6; do part "(Bildtakt|Frame pacing): .*(gleichmäßig|Pulldown|even|pulldown)" && return 0; [ $i = 2 ] && ui has "Signalweg|Signal path" && swipe_up; sleep 3; done; return 1; }
check "Signal path shows the frame pacing" pacing_shown
screenshot "$out/signal-pacing.png"
back 1
back 2
settings_pick "Bildrate anpassen|Match frame rate" "Nur nahtlos|Only when seamless"

# 7. HDR stays plain on HDR screens (mapped by Ordo on SDR screens), and Video info links to the signal path.
if [ "$hdr" = 1 ]; then
    relog
    open_folder 03-hdr.mp4
    ui tap 03-hdr.mp4 >/dev/null; wait_on viewer.video.VideoPlayerActivity 8; sleep 4
    if a shell dumpsys display | grep -q "mSupportedHdrTypes=\[\]"; then
        # An SDR screen: Ordo's own HDR to SDR pass (HdrToneMap) runs and reports its GPU time each second.
        check "an HDR10 clip on an SDR screen gets Ordo's HDR to SDR pass" eval 'log | grep "toneMap=true" && log | grep -- "-> ENGAGE"'
        sleep 3
        check "the HDR pass measures a peak and its GPU time" eval 'log | grep -E "hdr pass [0-9.]+ ms .* peak [0-9.]+ nits"'
        log | grep "hdr pass" | tail -3
    else
        check "an HDR10 clip on an HDR screen is not processed" eval 'log | grep "off=HDR" && ! log | grep -- "-> ENGAGE"'
    fi
    media_key PAUSE 1
    menu "Videoinfos|Video info"
    ui tap "Signalweg|Signal path" >/dev/null
    check "Video info opens the signal path, which names HDR10" ui wait "HDR: HDR10" 5
    back 1; back 2
else
    echo "SKIP HDR clip (ffmpeg without libx265)"
fi

finish
