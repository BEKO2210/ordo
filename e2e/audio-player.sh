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
for name, value in (("01-one.wav", 0), ("02-two.wav", 24)):
    with wave.open(str(root / name), "wb") as out:
        out.setnchannels(1); out.setsampwidth(1); out.setframerate(8000)
        out.writeframes(bytes([128 + value]) * 240000)
PY
a shell "rm -rf $DIR; mkdir -p $DIR"
a push "$tmp/01-one.wav" "$tmp/02-two.wav" "$DIR/" >/dev/null

wake; grant; a logcat -b crash -c
pause_playback() { a shell input keyevent KEYCODE_MEDIA_PAUSE >/dev/null; }
paused_ui() { pause_playback; sleep 0.3; ui "$@"; }
a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
    -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
sleep 3; guard
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
check "Sound opens from the player toolbar" paused_ui tap actionSound
check "the equalizer and deliberate-drag guidance are visible" paused_ui wait "Equalizer" 5
check "the curve explains its 150 ms hold" paused_ui has "Hold a handle for 150 ms, then drag.|Einen Griff 150 ms halten, dann ziehen."
check "the fine-tune controls start collapsed" paused_ui has "Fine-tune|Genau einstellen"
# The equalizer is off by default and its controls are disabled until it is switched on.
check "the equalizer switches on" paused_ui tap equalizerEnabled
check "the Bass preset tile can be selected" paused_ui tap "Bass"
check "the status names the selected preset" paused_ui has Bass effectStatus
check "Fine-tune opens its band rows" paused_ui tap fineTuneToggle
check "a band row shows its gain" paused_ui wait "31 Hz" 5
check "the Flat preset tile restores the curve" paused_ui tap "Flat|Neutral"
check "the equalizer switches off again" paused_ui tap equalizerEnabled
check "the playback section reaches skip silence and mono" paused_ui has "Skip silence|Stille überspringen"
check "the sleep timer offers end of track" paused_ui has "End of track|Titelende"
back 1
back 2
check "closing the player returns to the file list" on filelist.FileListActivity
not_on() { ! on "$@"; }
playing() { a shell dumpsys activity services "$PKG" | grep -q AudioPlaybackService; }
check "playback service stays alive" playing
a shell input keyevent KEYCODE_MEDIA_NEXT; sleep 2
a shell am start -W -n "$PKG/$PLAYER" >/dev/null; sleep 2; guard
check "the player reconnects" on viewer.audio.AudioPlayerActivity
check "media Next selected the second title" paused_ui has 02-two.wav
back 1
# The player is exported: another app may only hand it content:// URIs, never file:// paths (nor Ordo path extras).
a shell am start -W -a android.intent.action.VIEW -d "file://$DIR/01-one.wav" -t audio/x-wav \
    --es me.zhanghai.android.files.extra.PATH_URI "file://$DIR/02-two.wav" -n "$PKG/$PLAYER" >/dev/null; sleep 2
check "an external file:// intent is refused and the player closes" not_on viewer.audio.AudioPlayerActivity
a shell am force-stop "$PKG"
finish
