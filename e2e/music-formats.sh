#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Format matrix, WRITES: creates /storage/emulated/0/ordo-e2e-formats with 12-s files made by ffmpeg (MP3 CBR/VBR,
# AAC, FLAC 16/24-bit, Opus, Vorbis, WAV 16/24/32-bit float; 44.1 to 192 kHz; mono, stereo, 5.1) and removes it again.
# Each file, once with every Klang effect off and once with all of them on: it plays (state=PLAYING), the player shows
# the right duration, a tap on the seek bar at 3/4 lands there, playback goes on from there. Two FLAC and two MP3 halves
# of one tone must follow each other without a gap. The sound settings are written through run-as (debug builds) and
# restored afterwards. Needs ffmpeg on the host.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/ordo-e2e-formats
PREFS=shared_prefs/${PKG}_preferences.xml
tmp=$(mktemp -d)
restore_prefs() {
    a shell am force-stop "$PKG"
    [ -s "$tmp/prefs.xml" ] && a shell "run-as $PKG sh -c 'cat > $PREFS'" < "$tmp/prefs.xml"
}
trap 'restore_prefs; a shell "rm -rf $DIR"; rm -rf "$tmp"' EXIT
command -v ffmpeg >/dev/null || { echo "SKIP format matrix (no ffmpeg on the host)"; exit 0; }

DURATION=12
# name|extension|rate|channels|codec options
FORMATS=(
    "mp3-cbr-44k|mp3|44100|2|-c:a libmp3lame -b:a 320k"
    "mp3-vbr-48k|mp3|48000|2|-c:a libmp3lame -q:a 2"
    "mp3-mono-44k|mp3|44100|1|-c:a libmp3lame -b:a 128k"
    "aac-44k|m4a|44100|2|-c:a aac -b:a 256k"
    "aac-51-48k|m4a|48000|6|-c:a aac -b:a 384k"
    "flac16-44k|flac|44100|2|-c:a flac -sample_fmt s16"
    "flac24-96k|flac|96000|2|-c:a flac -sample_fmt s32 -bits_per_raw_sample 24"
    "flac24-192k|flac|192000|2|-c:a flac -sample_fmt s32 -bits_per_raw_sample 24"
    "flac16-51-48k|flac|48000|6|-c:a flac -sample_fmt s16"
    "flac24-mono-48k|flac|48000|1|-c:a flac -sample_fmt s32 -bits_per_raw_sample 24"
    "opus-48k|opus|48000|2|-c:a libopus -b:a 160k"
    "opus-51-48k|opus|48000|6|-c:a libopus -b:a 256k -mapping_family 1"
    "vorbis-44k|ogg|44100|2|-c:a libvorbis -q:a 5"
    "wav16-44k|wav|44100|2|-c:a pcm_s16le"
    "wav24-96k|wav|96000|2|-c:a pcm_s24le"
    "wav32f-192k|wav|192000|2|-c:a pcm_f32le"
    "wav16-51-48k|wav|48000|6|-c:a pcm_s16le"
)
# One tone per channel, so a channel mix-up would at least not be silent.
tones() {
    local n=$1 i out=""; for i in $(seq 1 "$n"); do out="$out|0.25*sin(2*PI*$((220 * i + 110))*t)"; done
    echo "${out#|}"
}
layout() { case $1 in 1) echo mono ;; 2) echo stereo ;; 6) echo 5.1 ;; esac; }
a shell "rm -rf $DIR; mkdir -p $DIR"
for entry in "${FORMATS[@]}"; do
    IFS='|' read -r name ext rate ch opts <<<"$entry"
    mkdir -p "$tmp/$name"
    # shellcheck disable=SC2086 # the codec options are separate words
    ffmpeg -v error -f lavfi -i "aevalsrc=$(tones "$ch"):s=$rate:d=$DURATION:c=$(layout "$ch")" $opts \
        "$tmp/$name/$name.$ext" || echo "FAIL ffmpeg could not make $name"
done
# Gapless: one continuous tone cut at exactly 6 s into two files.
mkdir -p "$tmp/gapless-flac" "$tmp/gapless-mp3"
ffmpeg -v error -f lavfi -i "aevalsrc=0.25*sin(2*PI*440*t)|0.25*sin(2*PI*550*t):s=44100:d=12:c=stereo" "$tmp/tone.wav"
for half in 1 2; do
    start=$(((half - 1) * 6))
    ffmpeg -v error -ss $start -t 6 -i "$tmp/tone.wav" -c:a flac "$tmp/gapless-flac/gap$half.flac"
    ffmpeg -v error -ss $start -t 6 -i "$tmp/tone.wav" -c:a libmp3lame -b:a 256k "$tmp/gapless-mp3/gap$half.mp3"
done
rm "$tmp/tone.wav"
a push "$tmp/." "$DIR/" >/dev/null

wake; grant; a logcat -b crash -c
a shell am force-stop "$PKG"
a shell "run-as $PKG cat $PREFS" > "$tmp/prefs.xml" 2>/dev/null
grep -q "<map" "$tmp/prefs.xml" || : > "$tmp/prefs.xml"
# set_effects on|off: the sound settings with every Klang effect on, or the earlier settings back.
set_effects() {
    a shell am force-stop "$PKG"
    if [ "$1" = off ]; then restore_prefs; return; fi
    local all='{"eq":true,"preset":"ROCK","crossfeedOn":true,"crossfeed":2,"widthOn":true,"width":130,"reverbOn":true,"reverbPreset":"HALL","reverbMix":30,"warmthOn":true,"warmth":2,"clarityOn":true,"clarity":2,"highsOn":true,"highs":1,"quietFull":true,"bassOn":true,"bass":600,"normalization":true}'
    python3 - "$tmp/prefs.xml" "$all" > "$tmp/prefs-on.xml" <<'PY'
import html, re, sys
xml = open(sys.argv[1]).read() or "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"
xml = re.sub(r'\s*<string name="audio_player_sound_settings">.*?</string>', "", xml, flags=re.S)
print(xml.replace("</map>", '    <string name="audio_player_sound_settings">'
                  + html.escape(sys.argv[2], quote=True) + "</string>\n</map>", 1), end="")
PY
    a shell "run-as $PKG mkdir -p shared_prefs"
    a shell "run-as $PKG sh -c 'cat > $PREFS'" < "$tmp/prefs-on.xml"
}

pause_playback() { a shell input keyevent KEYCODE_MEDIA_PAUSE >/dev/null; }
session() { a shell dumpsys media_session | grep -A25 "package=$PKG" | grep -m1 'state=PlaybackState'; }
session_state() { session | grep -oE 'state=[A-Z_]+\(' | head -1 | tr -d '(' | sed 's/state=//'; }
session_field() { session | grep -oE "$1=[-0-9]+" | head -1 | cut -d= -f2; }
playing() { [ "$(session_state)" = PLAYING ]; }
wait_playing() { local i; for i in 1 2 3 4 5 6 7 8; do playing && return 0; sleep 0.5; done; return 1; }
seconds() { awk -F: '{ s = 0; for (i = 1; i <= NF; i++) s = s * 60 + $i; print s }' <<<"${1//[^0-9:]/}"; }
# The player's elapsed plus remaining time, while paused at the start.
shown_duration() {
    local texts e r
    texts=$(ui texts); e=$(grep '|elapsed|' <<<"$texts" | cut -d'|' -f1); r=$(grep '|remaining|' <<<"$texts" | cut -d'|' -f1)
    [ -n "$e" ] && [ -n "$r" ] && echo $(($(seconds "$e") + $(seconds "$r")))
}
duration_ok() { local d; d=$(shown_duration); [ -n "$d" ] && [ $((d - DURATION)) -ge -1 ] && [ $((d - DURATION)) -le 1 ]; }
seek_three_quarters() {
    local l t r b
    read -r l t r b < <(ui bounds seekBar | head -1) || return 1
    a shell input tap $((l + (r - l) * 3 / 4)) $(((t + b) / 2)); sleep 1
    local p; p=$(session_field position)
    # 3/4 of 12 s is 9 s; the bar's own padding moves the tap a little.
    [ -n "$p" ] && [ "$p" -ge 7500 ] && [ "$p" -le 10500 ]
}
moves_on() { local p; p=$(session_field position); wait_playing && sleep 1.5 && [ "$(session_field position)" -gt "$p" ]; }
open_folder() {
    a shell am start -W -a android.intent.action.VIEW -d "file://$DIR/$1" -t inode/directory \
        -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
    sleep 2; guard; ui wait "$2" 10 >/dev/null
}

play_file() { # name file label
    open_folder "$1" "$2" || { fail "$3: the folder shows $2"; return; }
    ui tap "$2" >/dev/null; sleep 2
    check "$3: plays" wait_playing
    pause_playback; sleep 0.5
    a shell input keyevent KEYCODE_MEDIA_PREVIOUS >/dev/null; sleep 0.5 # back to 0:00 for the duration
    pause_playback; sleep 0.5
    check "$3: shows ${DURATION} s" duration_ok
    check "$3: a seek bar tap at 3/4 lands there" seek_three_quarters
    a shell input keyevent KEYCODE_MEDIA_PLAY >/dev/null
    check "$3: plays on from there" moves_on
    pause_playback
}

# gap_ms folder first second: the time between the end of the first file and the start of the second, measured from
# the session's own position stamps (position at an elapsedRealtime) before and after the switch.
gap_ms() {
    open_folder "$1" "$2" || return 1
    ui tap "$2" >/dev/null
    local i item first="" p1 u1 p2 u2 now
    for i in $(seq 1 60); do
        now=$(session); item=$(grep -oE 'active item id=[0-9-]+' <<<"$now")
        if [ -z "$first" ] && grep -q 'state=PLAYING' <<<"$now"; then
            first=$item; p1=$(grep -oE 'position=[0-9]+' <<<"$now" | cut -d= -f2); u1=$(grep -oE 'updated=[0-9]+' <<<"$now" | cut -d= -f2)
        elif [ -n "$first" ] && [ "$item" != "$first" ]; then
            p2=$(grep -oE 'position=[0-9]+' <<<"$now" | cut -d= -f2); u2=$(grep -oE 'updated=[0-9]+' <<<"$now" | cut -d= -f2)
            echo $(((u2 - p2) - (u1 - p1) - 6000)); return 0
        fi
        sleep 0.25
    done
    return 1
}
gapless() { # folder first label
    local gap; gap=$(gap_ms "$1" "$2"); pause_playback
    echo "INFO $3: gap ${gap:-?} ms"
    [ -n "$gap" ] && [ "${gap#-}" -le 150 ]
}

errors_before=$(a logcat -d | grep -c "ExoPlayerImplInternal.*Playback error")
for effects in off on; do
    set_effects $effects
    for entry in "${FORMATS[@]}"; do
        IFS='|' read -r name ext _ <<<"$entry"
        play_file "$name" "$name.$ext" "$name (Klang $effects)"
        back 1
    done
    check "gapless FLAC (Klang $effects): the second half follows without a gap" gapless gapless-flac gap1.flac "FLAC $effects"
    check "gapless MP3 (Klang $effects): the second half follows without a gap" gapless gapless-mp3 gap1.mp3 "MP3 $effects"
done
check "no playback error in the log" test "$(a logcat -d | grep -c "ExoPlayerImplInternal.*Playback error")" = "$errors_before"
finish
