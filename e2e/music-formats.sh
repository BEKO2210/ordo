#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# farm: timeout=1800
# Format matrix, WRITES: creates /storage/emulated/0/ordo-e2e-formats with 12-s files made by ffmpeg (MP3 CBR/VBR,
# AAC, FLAC 16/24-bit, Opus, Vorbis, WAV 16/24/32-bit float; 44.1 to 192 kHz; mono, stereo, 5.1) and removes it again.
# Each file, once with every Klang effect off and once with all of them on: it plays (state=PLAYING), the player shows
# the right duration, a tap on the seek bar at 3/4 lands there, playback goes on from there. Three FLAC and three MP3 parts
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
# Gapless: one continuous tone cut into three files of exactly 4 s.
mkdir -p "$tmp/gapless-flac" "$tmp/gapless-mp3"
ffmpeg -v error -f lavfi -i "aevalsrc=0.25*sin(2*PI*440*t)|0.25*sin(2*PI*550*t):s=44100:d=12:c=stereo" "$tmp/tone.wav"
for part in 1 2 3; do
    start=$(((part - 1) * 4))
    ffmpeg -v error -ss $start -t 4 -i "$tmp/tone.wav" -c:a flac "$tmp/gapless-flac/gap$part.flac"
    ffmpeg -v error -ss $start -t 4 -i "$tmp/tone.wav" -c:a libmp3lame -b:a 256k "$tmp/gapless-mp3/gap$part.mp3"
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
    # MDC's slider can hide its own node from uiautomator (W90, Android 16): measured as in player-seek.sh, between
    # the artist line (or the pills under it) and the times, as wide as the times row.
    t=$(ui bounds "artist|lyricsChip|queueChip" | awk '$4 > m { m = $4 } END { print m }')
    read -r l b _ _ < <(ui bounds elapsed | head -1) || return 1
    read -r _ _ r _ < <(ui bounds remaining | head -1) || return 1
    # A tap never seeks (house rule, PlayerSeekSlider): hold 400 ms, then drag to 3/4, like player-seek.sh.
    local y=$(((t + b) / 2)) xa=$((l + (r - l) / 4)) xb=$((l + (r - l) * 3 / 4))
    a shell "input motionevent DOWN $xa $y; sleep 0.4; input motionevent MOVE $(((xa + xb) / 2)) $y; \
        input motionevent MOVE $xb $y; sleep 0.3; input motionevent UP $xb $y"; sleep 1
    local p; p=$(session_field position)
    # 3/4 of 12 s is 9 s; the bar's own padding moves the tap a little.
    [ -n "$p" ] && [ "$p" -ge 7500 ] && [ "$p" -le 10500 ]
}
# The session stamps its position only on events; while playing, the position now is the stamp plus the time since
# (elapsedRealtime is CLOCK_BOOTTIME, the first field of /proc/uptime).
position_now() {
    local line now p u
    line=$(a shell "dumpsys media_session | grep -A25 'package=$PKG' | grep -m1 'state=PlaybackState'; cat /proc/uptime")
    p=$(grep -oE '[{ ]position=[0-9]+' <<<"$line" | head -1 | cut -d= -f2); u=$(grep -oE 'updated=[0-9]+' <<<"$line" | cut -d= -f2)
    now=$(tail -1 <<<"$line" | awk '{ printf "%d", $1 * 1000 }')
    if grep -q 'state=PLAYING' <<<"$line"; then echo $((p + now - u)); else echo "$p"; fi
}
moves_on() { # position when paused at the seek
    # 1.5 s later, at least 0.5 s on (a VBR seek lands roughly). A slow phone may reach the end of the 3 s left
    # first (A15: STOPPED at 12005 ms after a seek to 9000 ms): that played on from there too.
    wait_playing && sleep 1.5 && { playing || [ "$(session_state)" = STOPPED ]; } && [ "$(position_now)" -ge $(($1 + 500)) ] && return 0
    echo "INFO   state $(session_state), position $(position_now) ms after a seek to $1 ms" >&3; return 1
}
open_folder() {
    a shell am start -W -a android.intent.action.VIEW -d "file://$DIR/$1" -t inode/directory \
        -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
    guard; ui wait "$2" 10 >/dev/null
}

play_file() { # name file label
    open_folder "$1" "$2" || { fail "$3: the folder shows $2"; return; }
    ui tap "$2" >/dev/null
    check "$3: plays" wait_playing
    pause_playback; sleep 0.5
    a shell input keyevent KEYCODE_MEDIA_PREVIOUS >/dev/null; sleep 0.5 # back to 0:00 for the duration
    pause_playback; sleep 0.5
    check "$3: shows ${DURATION} s" duration_ok
    check "$3: held and dragged to 3/4, the bar lands there" seek_three_quarters
    local seek; seek=$(session_field position)
    a shell input keyevent KEYCODE_MEDIA_PLAY >/dev/null
    check "$3: plays on from there" moves_on "$seek"
    pause_playback
}

# gap_ms folder first: one tone cut into three 4-s files. The session stamps its position (position at an
# elapsedRealtime) at each switch to the next file; the second and third stamps lie exactly 4 s of audio apart when
# nothing is lost between the files. Comparing two switches cancels the player's start-up delay.
gap_ms() {
    open_folder "$1" "$2" || return 1
    ui tap "$2" >/dev/null
    local i item last="" now p u stamps=()
    for i in $(seq 1 80); do
        now=$(session); item=$(grep -oE 'active item id=[0-9-]+' <<<"$now")
        if grep -q 'state=PLAYING' <<<"$now" && [ "$item" != "$last" ]; then
            p=$(grep -oE '[{ ]position=[0-9]+' <<<"$now" | head -1 | cut -d= -f2); u=$(grep -oE 'updated=[0-9]+' <<<"$now" | cut -d= -f2)
            stamps+=("$((u - p))"); last=$item
            if [ ${#stamps[@]} = 3 ]; then echo $((stamps[2] - stamps[1] - 4000)); return 0; fi
        fi
        sleep 0.2
    done
    return 1
}
gapless() { # folder first label
    local gap; gap=$(gap_ms "$1" "$2"); pause_playback
    echo "INFO $3: gap ${gap:-?} ms" >&3
    [ -n "$gap" ] && [ "${gap#-}" -le 50 ]
}
exec 3>&1 # INFO lines reach the log past check's redirection

errors_before=$(a logcat -d | grep -c "ExoPlayerImplInternal.*Playback error")
# ONLY=name1,name2 runs just those formats (and the gapless pairs).
[ -n "${ONLY:-}" ] && mapfile -t FORMATS < <(printf '%s\n' "${FORMATS[@]}" | grep -E "^(${ONLY//,/|})\|")
for effects in off on; do
    set_effects $effects
    for entry in "${FORMATS[@]}"; do
        IFS='|' read -r name ext _ <<<"$entry"
        play_file "$name" "$name.$ext" "$name (Klang $effects)"
        back 1
    done
    check "gapless FLAC (Klang $effects): the parts follow without a gap" gapless gapless-flac gap1.flac "FLAC $effects"
    check "gapless MP3 (Klang $effects): the parts follow without a gap" gapless gapless-mp3 gap1.mp3 "MP3 $effects"
done
check "no playback error in the log" test "$(a logcat -d | grep -c "ExoPlayerImplInternal.*Playback error")" = "$errors_before"
finish
