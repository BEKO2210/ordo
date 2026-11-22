#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Klang under stress, WRITES: creates /storage/emulated/0/ordo-e2e-stress-klang with one 150-s stereo FLAC (48 kHz, or
# STRESS_RATE) and removes it again. With every Klang effect on (written through run-as, restored afterwards) the
# player's CPU share is measured over 20 s; then, while it plays, the equalizer switch and every effect switch in reach
# are tapped 20 times in a row as fast as adb can, and the preset tiles are switched 20 times. Afterwards it must still
# play, pause and play again, no crash, and the CPU share must come back. Prints INFO lines with the CPU numbers.
. "$(dirname "$0")/lib.sh" "$@"
export LC_ALL=C # awk prints and reads 10.3, not 10,3
DIR=/storage/emulated/0/ordo-e2e-stress-klang
PREFS=shared_prefs/${PKG}_preferences.xml
RATE=${STRESS_RATE:-48000}
tmp=$(mktemp -d)
restore_prefs() {
    a shell am force-stop "$PKG"
    [ -s "$tmp/prefs.xml" ] && a shell "run-as $PKG sh -c 'cat > $PREFS'" < "$tmp/prefs.xml"
}
trap 'restore_prefs; a shell "rm -rf $DIR"; rm -rf "$tmp"' EXIT
command -v ffmpeg >/dev/null || { echo "SKIP Klang stress (no ffmpeg on the host)"; exit 0; }
ffmpeg -v error -f lavfi -i "anoisesrc=color=pink:amplitude=0.1:d=150:r=$RATE" \
    -f lavfi -i "aevalsrc=0.2*sin(2*PI*220*t)|0.2*sin(2*PI*330*t):s=$RATE:d=150:c=stereo" \
    -filter_complex "[0][1]amix=inputs=2:normalize=0,aformat=channel_layouts=stereo" -c:a flac -sample_fmt s32 \
    "$tmp/stress.flac"
a shell "rm -rf $DIR; mkdir -p $DIR"; a push "$tmp/stress.flac" "$DIR/" >/dev/null

wake; grant; a logcat -b crash -c
a shell am force-stop "$PKG"
a shell "run-as $PKG cat $PREFS" > "$tmp/prefs.xml" 2>/dev/null
grep -q "<map" "$tmp/prefs.xml" || : > "$tmp/prefs.xml"
python3 - "$tmp/prefs.xml" > "$tmp/prefs-on.xml" <<'PY'
import html, re, sys
xml = open(sys.argv[1]).read() or "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"
xml = re.sub(r'\s*<string name="audio_player_sound_settings">.*?</string>', "", xml, flags=re.S)
on = ('{"eq":true,"preset":"ROCK","crossfeedOn":true,"crossfeed":2,"widthOn":true,"width":130,"reverbOn":true,'
      '"reverbPreset":"HALL","reverbMix":30,"warmthOn":true,"warmth":2,"clarityOn":true,"clarity":2,"highsOn":true,'
      '"highs":1,"quietFull":true,"bassOn":true,"bass":600,"normalization":true}')
print(xml.replace("</map>", '    <string name="audio_player_sound_settings">' + html.escape(on, quote=True)
                  + "</string>\n</map>", 1), end="")
PY
a shell "run-as $PKG mkdir -p shared_prefs"
a shell "run-as $PKG sh -c 'cat > $PREFS'" < "$tmp/prefs-on.xml"

session_state() { a shell dumpsys media_session | grep -A25 "package=$PKG" | grep -m1 'state=PlaybackState' | grep -oE 'state=[A-Z_]+\(' | tr -d '(' | sed 's/state=//'; }
wait_state() { local i; for i in 1 2 3 4 5 6 7 8 9 10; do [ "$(session_state)" = "$1" ] && return 0; sleep 0.5; done; return 1; }
# The app's CPU share of one core over $1 seconds, from /proc/<pid>/stat (utime + stime, in clock ticks of 1/100 s).
cpu() {
    local pid t1 t2; pid=$(a shell pidof "$PKG" | tr -d '\r' | awk '{print $1}'); [ -n "$pid" ] || return 1
    t1=$(a shell cat /proc/$pid/stat | awk '{print $14 + $15}'); sleep "$1"
    t2=$(a shell cat /proc/$pid/stat | awk '{print $14 + $15}')
    awk -v d=$((t2 - t1)) -v s="$1" 'BEGIN { printf "%.1f", d / s }'
}
cores() { a shell nproc | tr -d '\r'; }
a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
    -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
guard
check "the test folder opens" ui wait stress.flac 10
ui tap stress.flac >/dev/null
check "the FLAC plays with every effect on" wait_state PLAYING
before=$(cpu 20)
echo "INFO CPU with every effect on, $RATE Hz: ${before:-?} % of one core ($(cores) cores)"
# A playing screen cannot be dumped reliably: every read of the screen happens paused, every storm while playing.
pause() { media_key PAUSE 0.4; }
resume() { a shell input keyevent KEYCODE_MEDIA_PLAY; wait_state PLAYING; }
pause
check "Sound opens" ui tap actionSound
sleep 1.5
# tap_fast BOUNDS: 20 taps in one adb call, as fast as the device takes them; an even count leaves the switch as it was.
tap_fast() {
    local l t r b; read -r l t r b <<<"$1"
    a shell "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do input tap $(((l + r) / 2)) $(((t + b) / 2)); done"
}
toggled=0
eq=$(ui bounds equalizerEnabled | head -1)
mapfile -t presets < <(ui bounds "Rock|Pop|Bass|Jazz|Klassik|Classical|Gesang|Vocal|Höhen|Treble|Nacht|Night" | head -4)
storm_start=$SECONDS
# Back to the start, so the 150-s title does not end during the storm.
a shell input keyevent KEYCODE_MEDIA_PREVIOUS; resume
[ -n "$eq" ] && { tap_fast "$eq"; toggled=$((toggled + 1)); }
# Preset tiles in turn, 20 switches while it plays.
for i in $(seq 0 19); do
    [ ${#presets[@]} -gt 0 ] || break
    read -r l t r b <<<"${presets[$((i % ${#presets[@]}))]}"; a shell input tap $(((l + r) / 2)) $(((t + b) / 2))
done
echo "INFO preset tiles switched: $([ ${#presets[@]} -gt 0 ] && echo 20 || echo 0) times over ${#presets[@]} tiles"
for round in 1 2 3 4 5 6; do
    pause; mapfile -t boxes < <(ui bounds toggle); resume
    for box in "${boxes[@]}"; do [ -n "$box" ] && { tap_fast "$box"; toggled=$((toggled + 1)); }; done
    swipe_up
done
echo "INFO switches tapped 20 times each: $toggled, storm $((SECONDS - storm_start)) s"
# A tap may land elsewhere once a card grows or shrinks; what counts is that nothing is stuck afterwards.
state=$(session_state); echo "INFO state after the storm: $state"
check "after the storm it is playing or paused, not stuck or failed" grep -qE '^(PLAYING|PAUSED)$' <<<"$state"
a shell logcat -d | grep -E "ExoPlayerImplInternal.*(Playback error|Exception)|AudioTrack.*(error|fail)" | tail -3 | sed 's/^/INFO log: /'
back 1
check "it plays" resume
a shell input keyevent KEYCODE_MEDIA_PAUSE
check "it pauses" wait_state PAUSED
check "and plays again" resume
threads() { # the app's four busiest threads, by name
    a shell "top -H -b -n 1 -o TID,%CPU,CMD -p $(a shell pidof "$PKG" | tr -d '\r' | awk '{print $1}')" |
        grep -E "^ *[0-9]+ +[0-9.]+ " | sort -k2 -rn | head -4 | awk '{ print "INFO   thread " $3 ": " $2 " %" }'
}
threads; first=$(cpu 20); threads; second=$(cpu 20); after=$(cpu 20)
# Loudness levelling measures an untagged title once on a low-priority thread (a 192 kHz title takes a while on a weak
# device), so the first minute may be busy; what counts is where it settles.
echo "INFO CPU afterwards, three 20-s windows: ${first:-?} / ${second:-?} / ${after:-?} % of one core"
check "the CPU share settles back (at most 1.5 × before + 3 points)" \
    awk -v a="${after:-999}" -v b="${before:-0}" 'BEGIN { exit !(a <= b * 1.5 + 3) }'
a shell input keyevent KEYCODE_MEDIA_PAUSE
finish
