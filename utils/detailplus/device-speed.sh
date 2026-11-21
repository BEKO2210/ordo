#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Detail plus speed on a device (docs/VIDEO-PLAYER.md): device-speed.sh <serial> <upscaler label> <seconds> <clip>...
# Clips from device-clips.sh (~/dpf-clips). Landscape, media volume 0, sets "Upscaling", plays each clip for <seconds>
# and prints the network's GPU time per second (debug log, at the top clock where readable), dropped frames, the
# Adreno GPU clock (sampled every 5 s) and the thermal status before and after. With PKG/E2E_GRANT as for e2e/.
# A build with a per-pass timer patch (not in the repo) also logs "OrdoDPProf" lines; they are printed when present.
. "$(dirname "$0")/../../e2e/lib.sh" "$1"
LABEL=$2; SECS=$3; shift 3
DIR=/storage/emulated/0/ordo-dpf
TAG=OrdoVideoPicture
out=${OUT:-$HOME/dpf-out}; mkdir -p "$out"
rotation=$(a shell settings get system user_rotation | tr -d '\r'); auto=$(a shell settings get system accelerometer_rotation | tr -d '\r')
trap 'a shell am force-stop $PKG; a shell setprop log.tag.$TAG "\"\""; a shell settings put system user_rotation "${rotation:-0}"; a shell settings put system accelerometer_rotation "${auto:-1}"' EXIT
a shell "mkdir -p $DIR/Anime"
for c in "$@"; do a shell "ls \"$DIR/$c\"" >/dev/null 2>&1 || a push "$HOME/dpf-clips/$c" "$DIR/$c" >/dev/null; done
wake; grant
a shell settings put system accelerometer_rotation 0; a shell settings put system user_rotation 1; sleep 3
a shell am force-stop "$PKG"
a shell setprop log.tag.$TAG DEBUG
upscaling() {
    a shell am start -W -n "$PKG/me.zhanghai.android.files.viewer.video.VideoLauncherActivity" >/dev/null; sleep 2; guard
    ui tap actionSettings >/dev/null; sleep 2
    scroll_to "Hochskalieren|Upscaling" >/dev/null
    ui tap "Hochskalieren|Upscaling" >/dev/null; sleep 1.5
    ui has "$1" || scroll_to "$1" 3 >/dev/null # landscape A8: the sheet scrolls
    ui tap "$1" >/dev/null; sleep 1.5
    back 1; back 1
}
upscaling "$LABEL"
thermal() { a shell dumpsys thermalservice | tr -d '\r' | grep -m1 -E "Thermal Status|mStatus"; }
for c in "$@"; do
    name=$(basename "$c"); f="$out/$(echo "$name" | tr -c 'A-Za-z0-9.\n' _).log"
    echo "== $c  $(thermal)"
    a logcat -c
    # Streamed while it plays: Samsung's log buffer drops a 3-min run's lines before a dump at the end.
    "$ADB" -s "$SERIAL" logcat -s "OrdoDPProf:I" "$TAG:D" > "$f.raw" 2>/dev/null & logger=$!
    d=$(dirname "$DIR/$c")
    a shell am start -W -a android.intent.action.VIEW -d "file://$d" -t inode/directory \
        -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
    sleep 3; guard; ui wait "$name" 10 >/dev/null; ui tap "$name" >/dev/null
    wait_on viewer.video.VideoPlayerActivity 8 || { echo "FAIL player did not open"; kill $logger; continue; }
    sleep 5; guard
    [ "$(media_volume)" = 0 ] || { echo "ABORT volume"; exit 2; }
    # GPU clock while playing (Adreno only; the governor lowers it while the GPU has slack).
    end=$((SECONDS + SECS)); : > "$f.clk"
    while [ $SECONDS -lt $end ]; do
        a shell cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null | tr -d '\r' >> "$f.clk"; sleep 5
    done
    LC_ALL=C awk '$1>0 {s+=$1; n++} END { if (n) printf "   gpu clock: mean %.0f MHz over %d samples\n", s/n/1e6, n }' "$f.clk"
    kill $logger 2>/dev/null; tr -d '\r' < "$f.raw" > "$f"; rm -f "$f.raw"
    grep -E "effects:|off:|auto:" "$f" | tail -3
    grep "steps" "$f" | tail -1
    grep -oE "detail plus [A-Z]+: [0-9.]+ ms over [0-9]+, [0-9]+ dropped" "$f" | LC_ALL=C awk '{ms+=$4; n++; d+=$8; if ($4>mx) mx=$4} END { if (n) printf "   network: %d s timed, mean %.2f ms, max %.2f ms, dropped %d\n", n, ms/n, mx, d }'
    grep "dropped total" "$f" | sed -n '1p;$p' | tr '\n' ' '; echo
    echo "   after: $(thermal)"
    a shell am force-stop "$PKG"; sleep 3
done
