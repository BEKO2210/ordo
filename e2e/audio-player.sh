#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Music player, WRITES: creates /sdcard/ordo-e2e-audio with two tiny WAV files and removes it afterwards.
# Opens the first from the folder list, closes the controller screen while playback continues, sends Media Next,
# then opens the controller without a new queue and checks that it reconnected to the second title.
# Finally an external file:// VIEW intent must be refused.
. "$(dirname "$0")/lib.sh" "$@"
# /storage/emulated/0, not /sdcard: on real devices Ordo cannot list through the /sdcard link ("Root isn't available").
DIR=/storage/emulated/0/ordo-e2e-audio
PLAYER=me.zhanghai.android.files.viewer.audio.AudioPlayerActivity
tmp=$(mktemp -d); trap 'a shell "rm -rf $DIR"; rm -rf "$tmp"' EXIT

python3 - "$tmp" <<'PY'
import pathlib, struct, sys, wave
root = pathlib.Path(sys.argv[1])
with wave.open(str(root / "01-one.wav"), "wb") as out:
    out.setnchannels(1); out.setsampwidth(1); out.setframerate(8000)
    out.writeframes(bytes([128]) * 240000)
# The second title is 16-bit stereo like real music: only then does Ordo's mono/balance processor run.
with wave.open(str(root / "02-two.wav"), "wb") as out:
    out.setnchannels(2); out.setsampwidth(2); out.setframerate(44100)
    out.writeframes(struct.pack("<hh", 24, -24) * (44100 * 30))
PY
a shell "rm -rf $DIR; mkdir -p $DIR"
a push "$tmp/01-one.wav" "$tmp/02-two.wav" "$DIR/" >/dev/null

wake; grant; a logcat -b crash -c
pause_playback() { a shell input keyevent KEYCODE_MEDIA_PAUSE >/dev/null; }
paused_ui() { pause_playback; sleep 0.3; ui "$@"; }
a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
    -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
guard
check "the test folder opens" paused_ui wait 01-one.wav 10
paused_ui tap 01-one.wav >/dev/null; sleep 3; guard
pause_playback; sleep 1
check "audio opens Ordo's player" on viewer.audio.AudioPlayerActivity
check "the tapped title is shown" paused_ui has 01-one.wav
check "the complete transport row is visible" paused_ui visible playPause
touch_target() {
    # Pause before each dump: a changing playback screen cannot be read reliably.
    local l t r b dpi i
    pause_playback; sleep 0.3
    dpi=$(a shell wm density | tail -1 | sed 's/.*: //' | tr -d '\r')
    for i in 1 2 3 4; do
        read -r l t r b < <(paused_ui bounds "$1" | head -1) || return 1
        [ $((r - l)) -ge $((48 * dpi / 160)) ] && [ $((b - t)) -ge $((48 * dpi / 160)) ] && return 0
        sleep 0.5
    done
    return 1
}
check "shuffle keeps its 48 dp touch target" touch_target shuffle
check "repeat keeps its 48 dp touch target" touch_target repeat
# "Play on…" hands over to the system: its output picker (or, before Android 11, the Bluetooth settings) takes focus.
system_focus() {
    local i; for i in 1 2 3 4 5 6; do
        a shell dumpsys window | grep -m1 mCurrentFocus | grep -qv "$PKG" && return 0; sleep 0.5
    done
    return 1
}
check "Play on is in the player toolbar" paused_ui tap actionOutput
check "Play on opens the system output picker" system_focus
a shell input keyevent KEYCODE_BACK; sleep 1
check "Sound opens from the player toolbar" paused_ui tap actionSound
check "the equalizer and deliberate-drag guidance are visible" paused_ui wait "Equalizer" 5
sleep 1 # the sheet is still sliding up: a tap now would land where the switch is about to leave
# The sheet is long: scroll down until the row appears (at most ten swipes; six effect cards sit before playback).
scrolled_to() { local i; for i in 1 2 3 4 5 6 7 8 9 10 11; do paused_ui has "$1" && return 0; swipe_up; done; return 1; }
# On a landscape phone the curve alone fills the sheet and the header (switch, status) scrolls away with it: the
# sheet opens again at its top to reach them.
# A reopened sheet starts with Fine-tune collapsed again (fine_open remembers whether its band rows are shown).
sound_at_top() { back 1; paused_ui tap actionSound >/dev/null; sleep 1.5; fine_open=0; }
check "the curve explains its 150 ms hold" scrolled_to "Hold a handle for 150 ms, then drag.|Einen Griff 150 ms halten, dann ziehen."
check "the fine-tune controls start collapsed" scrolled_to "Fine-tune|Genau einstellen"
paused_ui has equalizerEnabled || sound_at_top
# The equalizer is off by default and its controls are disabled until it is switched on.
# The switch is set to a state, not toggled: the setting is remembered between runs.
equalizer() {
    local i; for i in 1 2 3; do
        if paused_ui checked Equalizer; then [ "$1" = on ] && return 0; else [ "$1" = off ] && return 0; fi
        paused_ui tap equalizerEnabled
    done
    return 1
}
check "the equalizer switches on" equalizer on
# The preset row opens at the selected tile (a later one when another preset is remembered): back to its start
# first, otherwise Bass and Flat lie off screen on a phone.
presets_to_start() {
    local l t r b i
    read -r l t r b < <(paused_ui bounds presets | head -1) || return 1
    read -r W H < <(screen_size)
    [ "$l" -lt 0 ] && l=0; [ "$r" -gt "$W" ] && r=$W
    for i in 1 2 3; do a shell input swipe $((l + 60)) $(((t + b) / 2)) $((r - 60)) $(((t + b) / 2)) 250; sleep 0.6; done
}
# The preset row lies right above the fine-tune card: with that card in sight the row is too.
scrolled_to fineTuneToggle
presets_to_start
check "the Bass preset tile can be selected" paused_ui tap "Bass"
# The status follows the player service's answer: give it a moment.
settled() { local i; for i in 1 2 3 4 5 6; do paused_ui has "$@" && return 0; sleep 0.5; done; return 1; }
paused_ui has effectStatus || sound_at_top
check "the status names the selected preset" settled Bass effectStatus
fine_tune_opened() { scrolled_to fineTuneToggle && paused_ui tap fineTuneToggle && fine_open=1; }
check "Fine-tune opens its band rows" fine_tune_opened
# Everything at the top first: on a phone the band rows push the rest below the screen.
presets_to_start
check "the Flat preset tile restores the curve" paused_ui tap "Flat|Neutral"
paused_ui has equalizerEnabled || sound_at_top
check "the equalizer switches off again" equalizer off
band_row() { [ "${fine_open:-0}" = 1 ] || fine_tune_opened || return 1; scrolled_to "31 Hz"; }
check "a band row shows its gain" band_row
check "the playback section reaches skip silence and mono" scrolled_to "Skip silence|Stille überspringen"
check "the sleep timer offers end of track" scrolled_to "End of track|Titelende"
back 1
back 2
check "closing the player returns to the file list" on filelist.FileListActivity
not_on() { ! on "$@"; }
playing() { a shell dumpsys activity services "$PKG" | grep -q AudioPlaybackService; }
check "playback service stays alive" playing
a shell input keyevent KEYCODE_MEDIA_NEXT; sleep 2
# A stereo title must actually play: a failed player leaves the session in ERROR instead of PLAYING.
# Next keeps the paused state from the checks above, so press play first.
session_playing() { a shell dumpsys media_session | grep -A25 "package=$PKG" | grep -q "state=PLAYING"; }
media_key PLAY 3
check "a stereo title plays" session_playing
a shell am start -W -n "$PKG/$PLAYER" >/dev/null
check "the player reconnects" wait_on viewer.audio.AudioPlayerActivity 4; guard
check "media Next selected the second title" paused_ui has 02-two.wav
back 1
# The player is exported: another app may only hand it content:// URIs, never file:// paths (nor Ordo path extras).
a shell am start -W -a android.intent.action.VIEW -d "file://$DIR/01-one.wav" -t audio/x-wav \
    --es me.zhanghai.android.files.extra.PATH_URI "file://$DIR/02-two.wav" -n "$PKG/$PLAYER" >/dev/null; sleep 2
check "an external file:// intent is refused and the player closes" not_on viewer.audio.AudioPlayerActivity
a shell am force-stop "$PKG"
finish
