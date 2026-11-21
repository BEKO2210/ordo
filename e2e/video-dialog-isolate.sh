#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# "Dialog freistellen plus", WRITES: a 48 kHz stereo clip made by ffmpeg in /storage/emulated/0/ordo-e2e-isolate
# (removed again). ⋮ › Sound shows the card on a 64-bit device, off. The model of an earlier run is deleted first, so
# the consent is checked each time: switching on asks; Cancel leaves it off and downloads nothing; Load fetches the
# model, the switch then turns on by itself and the film plays on; the debug log reports a real-time factor (RTF)
# ≤ 0.7, or the card is gone (too slow); with Clear dialogue on as well it plays on; switching off and on keeps it
# playing; no crash. The model stays afterwards (8 MB in the app's files).
# Whether it sounds better needs ears: SKIP, by hand (~/ordo-team/df3-hoertest).
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/ordo-e2e-isolate
tmp=$(mktemp -d); trap 'a shell "rm -rf $DIR"; rm -rf "$tmp"' EXIT
command -v ffmpeg >/dev/null || { echo "SKIP dialog isolation (no ffmpeg on the host)"; exit 0; }
ffmpeg -v error -f lavfi -i "testsrc=size=1280x720:rate=30:duration=120" \
    -f lavfi -i "sine=frequency=220:duration=120:sample_rate=48000" -ac 2 -ar 48000 \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -b:a 192k -shortest "$tmp/01-isolate.mp4" \
    || echo "FAIL ffmpeg could not make 01-isolate.mp4"
a shell "rm -rf $DIR; mkdir -p $DIR"
a push "$tmp/." "$DIR/" >/dev/null

wake; grant; a logcat -b crash -c; a logcat -c
a shell am force-stop "$PKG"
session() {
    local d; d=$(a shell dumpsys media_session)
    { grep -A25 "id\.video\.[-0-9a-f]* $PKG" <<<"$d" || grep -A25 "package=$PKG" <<<"$d"; } | grep -m1 'state=PlaybackState'
}
playing() { session | grep -q 'state=PLAYING'; }
wait_playing() { local i; for i in $(seq 1 16); do playing && return 0; sleep 0.5; done; return 1; }
part() { ui texts | grep -E -- "$1" >/dev/null; }
a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
    -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
sleep 3; guard; ui wait 01-isolate.mp4 10 >/dev/null
ui tap 01-isolate.mp4 >/dev/null; wait_on viewer.video.VideoPlayerActivity 8
check "the 48 kHz clip plays" wait_playing
video_menu "Klang|Sound"
abi=$(a shell getprop ro.product.cpu.abi | tr -d '\r')
if ! scroll_to "Dialog freistellen plus|Dialogue isolation plus" 4; then
    case "$abi" in
        arm64-v8a|x86_64) fail "the card is shown on $abi (unless this device was measured too slow before)" ;;
        *) pass "no card on $abi (no library for it)" ;;
    esac
    back; a shell am force-stop "$PKG"; finish; exit
fi
a shell run-as "$PKG" rm -f files/models/deepfilternet3_onnx.tar.gz
ui tap "Dialog freistellen plus|Dialogue isolation plus" >/dev/null; sleep 1.5
check "switching on asks before any download" ui has "Sprachmodell laden?|Load the speech model?"
ui tap "Abbrechen|Cancel" >/dev/null; sleep 1
check "Cancel downloads nothing" eval '! a shell run-as "$PKG" ls files/models/deepfilternet3_onnx.tar.gz'
ui tap "Dialog freistellen plus|Dialogue isolation plus" >/dev/null; sleep 1.5
ui tap "Laden|Load" >/dev/null
for _ in $(seq 1 60); do a shell run-as "$PKG" ls files/models/deepfilternet3_onnx.tar.gz >/dev/null 2>&1 && break; sleep 2; done
check "the model is there after Load" a shell run-as "$PKG" ls files/models/deepfilternet3_onnx.tar.gz
sleep 6
rtf=$(a logcat -d -s OrdoVideoSound | grep -o 'DeepFilterNet3: loaded=true rtf=[0-9.E-]*' | tail -1 | sed 's/.*rtf=//')
echo "INFO DeepFilterNet3 RTF on this device: ${rtf:-not logged}"
if [ -n "$rtf" ] && python3 -c "import sys; sys.exit(0 if float('$rtf') <= 0.7 else 1)"; then
    pass "the model runs fast enough here (RTF $rtf)"
    check "with Dialog isolation plus on it plays on" wait_playing
    scroll_to "Dialog-Verstärker|Clear dialogue" 3; ui tap "Dialog-Verstärker|Clear dialogue" >/dev/null; sleep 1.5
    check "together with Clear dialogue it plays on" wait_playing
    ui tap "Dialog-Verstärker|Clear dialogue" >/dev/null; sleep 1
    scroll_to "Dialog freistellen plus|Dialogue isolation plus" 3
    ui tap "Dialog freistellen plus|Dialogue isolation plus" >/dev/null; sleep 1.5
    check "switched off it plays on" wait_playing
    ui tap "Dialog freistellen plus|Dialogue isolation plus" >/dev/null; sleep 1.5
    check "switched on again it plays on" wait_playing
    ui tap "Dialog freistellen plus|Dialogue isolation plus" >/dev/null; sleep 1
else
    check "too slow here: the card is gone" eval '! ui has "Dialog freistellen plus|Dialogue isolation plus"'
fi
back; back 2
echo "SKIP how the isolated dialogue sounds (by ear, ~/ordo-team/df3-hoertest)"
check "no crash in the whole run" test "$(crashes)" = 0
a shell am force-stop "$PKG"
finish
