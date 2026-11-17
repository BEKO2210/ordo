#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Sound effects, WRITES: creates /storage/emulated/0/ordo-e2e-klang with one 16-bit stereo WAV and removes it again.
# Opens the Sound sheet, reaches every effect card of the lower part, switches each one on and checks that its live
# picture reports the value; then a 16-bit stereo title must still reach state=PLAYING with all effects on.
# Afterwards every effect is switched off again, so the device keeps its earlier sound.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/ordo-e2e-klang
tmp=$(mktemp -d); trap 'a shell "rm -rf $DIR"; rm -rf "$tmp"' EXIT

# Stereo with a different signal per channel: only 16-bit stereo runs through Ordo's own processors.
python3 - "$tmp" <<'PY'
import math, pathlib, struct, sys, wave
with wave.open(str(pathlib.Path(sys.argv[1]) / "klang-stereo.wav"), "wb") as out:
    out.setnchannels(2); out.setsampwidth(2); out.setframerate(44100)
    out.writeframes(b"".join(struct.pack("<hh", int(8000 * math.sin(i / 20)), int(8000 * math.sin(i / 13)))
                             for i in range(44100 * 40)))
PY
a shell "rm -rf $DIR; mkdir -p $DIR"
a push "$tmp/klang-stereo.wav" "$DIR/" >/dev/null

wake; grant; a logcat -b crash -c
pause_playback() { a shell input keyevent KEYCODE_MEDIA_PAUSE >/dev/null; }
paused_ui() { pause_playback; sleep 0.3; ui "$@"; }
a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
    -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
sleep 3; guard
check "the test folder opens" paused_ui wait klang-stereo.wav 10
paused_ui tap klang-stereo.wav >/dev/null; sleep 3; guard
pause_playback; sleep 1
check "the stereo title opens in Ordo's player" on viewer.audio.AudioPlayerActivity
check "Sound opens from the player toolbar" paused_ui tap actionSound
sleep 1.5 # the sheet slides up

scrolled_to() { local i; for i in 1 2 3 4 5 6 7 8 9 10 11; do paused_ui has "$1" && return 0; swipe_up; done; return 1; }
# set_switch TITLE on|off: the effect's switch carries the title as its description.
set_switch() {
    local i; for i in 1 2 3; do
        if paused_ui checked "$1"; then [ "$2" = on ] && return 0; else [ "$2" = off ] && return 0; fi
        paused_ui tap "$1" toggle >/dev/null; sleep 0.8
    done
    return 1
}
# The live picture says "Title: value"; switched on, the value is no longer "Off". It sits under the title, so a
# short scroll brings it in when the title stands at the bottom edge.
nudge() { read -r W H < <(screen_size); a shell input swipe $((W / 2)) $((H * 6 / 10)) $((W / 2)) $((H * 4 / 10)) 400; sleep 1; }
# ui.py texts prints "text|id|description" with the short id.
picture_shows() { ui texts | grep -E "^\|picture\|($1): " | grep -vqE ": (Aus|Off)$"; }
picture_on() { local i; for i in 1 2 3; do pause_playback; picture_shows "$1" && return 0; nudge; done; return 1; }

# Mono greys out room sound and width: make sure it is off first. Then the sheet opens again at its top; swiping down
# at the top would drag the whole sheet away.
check "the mono row is reachable" scrolled_to "Mono audio|Mono-Audio|Mono"
check "mono is off" set_switch "Mono audio|Mono-Audio|Mono" off
back 1
check "Sound opens again at the top" paused_ui tap actionSound
sleep 1.5

EFFECTS=("Bass boost|Bassverstärkung" "Room sound for headphones|Raumklang für Kopfhörer" "Stereo width|Stereobreite"
    "Reverb|Hall" "Analogue warmth|Analoge Wärme" "Clarity|Klarheit")
for effect in "${EFFECTS[@]}"; do
    name=${effect%%|*}
    if ! scrolled_to "$effect"; then
        # Bass and clarity give way to a "not available" line on devices without the platform effect.
        if paused_ui has "$name: not available on this device|${effect#*|}: auf diesem Gerät nicht verfügbar"; then
            echo "SKIP $name (not available on this device)"; continue
        fi
        fail "the $name card is reachable"; continue
    fi
    pass "the $name card is reachable"
    check "$name switches on" set_switch "$effect" on
    check "the $name picture reports its value" picture_on "$effect"
done
check "a reverb room can be chosen" paused_ui tap "Concert hall|Konzertsaal"
check "the sleep timer shows its picture" scrolled_to "Sleep timer: Off|Schlaf-Timer: Aus|Einschlaf-Timer: Aus"
back 1

session_playing() { a shell dumpsys media_session | grep -A25 "package=$PKG" | grep -q "state=PLAYING"; }
a shell input keyevent KEYCODE_MEDIA_PLAY; sleep 4
check "a 16-bit stereo title plays with all effects on" session_playing
sleep 4
check "it is still playing seconds later" session_playing
pause_playback

# Leave the device as it was: every effect off again.
check "Sound opens for clean-up" paused_ui tap actionSound
sleep 1.5
for effect in "${EFFECTS[@]}"; do
    scrolled_to "$effect" >/dev/null 2>&1 || continue
    check "${effect%%|*} switches off again" set_switch "$effect" off
done
back 1
back 1
a shell am force-stop "$PKG"
finish
