#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Run it on one device with gesture navigation and one with three buttons (the Tab and the W90 here).
# The player's lyrics, cover swipe and queue, WRITES: three silent songs in /storage/emulated/0/Music/OrdoE2E-Stage2,
# the first with a synced .lrc beside it, the second with a 20,000-line .lrc (a crafted file: the app shows at most
# 5,000 lines and must stay responsive), removed afterwards.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/Music/OrdoE2E-Stage2
LIBRARY=me.zhanghai.android.files.viewer.audio.MusicLauncherActivity
tmp=$(mktemp -d); trap 'a shell "rm -rf $DIR"; rm -rf "$tmp"' EXIT
python3 - "$tmp" <<'PY'
import struct, sys
out = sys.argv[1]
def id3(title, album):
    t = lambda s: b"\0" + s.encode("latin-1")
    body = b"".join(n.encode() + struct.pack(">I", len(d)) + b"\0\0" + d
                    for n, d in [("TIT2", t(title)), ("TPE1", t("Ordoe2e Band")), ("TALB", t(album))])
    s = len(body)
    return b"ID3\x03\0\0" + bytes([(s >> 21) & 127, (s >> 14) & 127, (s >> 7) & 127, s & 127]) + body
silence = (b"\xff\xfb\x90\x00" + bytes(413)) * (90 * 44100 // 1152)
for i, title in enumerate(["Ordoe2e Lyrics A", "Ordoe2e Lyrics B", "Ordoe2e Lyrics C"], 1):
    open(f"{out}/s{i}.mp3", "wb").write(id3(title, "Ordoe2e Stage Two") + silence)
open(f"{out}/s1.lrc", "w").write("\n".join(f"[00:{2 * i:02d}.00]Ordoe2e line {i}" for i in range(30)) + "\n")
open(f"{out}/s2.lrc", "w").write("\n".join(f"[{i // 6000 % 100:02d}:{i // 100 % 60:02d}.{i % 100:02d}]Zeile {i}"
                                           for i in range(20000)) + "\n")
PY
a shell "mkdir -p $DIR"
for f in "$tmp"/*; do a push "$f" "$DIR/" > /dev/null
    a shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$DIR/$(basename "$f")" > /dev/null 2>&1
done
sleep 3
wake; grant; a logcat -b crash -c
pause() { a shell input keyevent KEYCODE_MEDIA_PAUSE > /dev/null; sleep 1.5; }
song() { a shell dumpsys media_session | grep -A25 "package=$PKG" | grep -m1 -o "description=[^,]*"; }
a shell am force-stop "$PKG"
a shell am start -W -n "$PKG/$LIBRARY" > /dev/null; guard
ui tap "Titel|Songs" > /dev/null
search() { ui tap actionSearch > /dev/null && sleep 1 && a shell input text "$1" && a shell input keyevent KEYCODE_ENTER && sleep 2; }
search Ordoe2e
check "the first song opens the player" eval 'ui tap "Ordoe2e Lyrics A" title && wait_on viewer.audio.AudioPlayerActivity 8'
pause
ui wait lyricsChip 10 > /dev/null || { echo "DIAG:"; ui texts | head -14 | sed "s/^/  /"; a shell ls $DIR | tr "\n" " "; echo; }
check "a song with a .lrc offers lyrics" ui has lyricsChip
check "the lyrics layer opens with the song's lines" eval 'ui tap lyricsChip && ui wait "Ordoe2e line 0" 5'
# Gesture or three-button navigation (navigation_mode 2 or 0, whatever the device uses): the lyrics' play button
# stays clear of the bottom strip where a swipe goes home, and nothing in the player needs a swipe up from the edge.
nav=$(a shell settings get secure navigation_mode | tr -d '\r')
read -r W H < <(screen_size)
density=$(a shell wm density | tail -1 | grep -o '[0-9]*$')
read -r _ _ _ pb < <(ui bounds lyricsPlayPause | head -1)
check "the lyrics' play button sits above the home gesture strip (navigation_mode $nav)" \
    test "${pb:-$H}" -le $((H - 24 * density / 160))
back; pause
check "back closes the lyrics, not the player" on viewer.audio.AudioPlayerActivity
check "the labelled 'up next' pill opens the queue by a tap" eval 'ui tap queueChip && ui wait "Ordoe2e Lyrics B" 8'
back; pause
# Cover swipe: sideways changes the song, up and down never does.
read -r c1 d1 c2 d2 < <(ui bounds artworkCard | head -1)
cy=$(((d1 + d2) / 2)); cx=$(((c1 + c2) / 2))
before=$(song)
a shell input swipe $cx $((d1 + 40)) $cx $((d2 - 40)) 250; sleep 2
check "a vertical drag on the cover keeps the song" test "$(song)" = "$before"
a shell input swipe $((c1 + (c2 - c1) * 8 / 10)) $cy $((c1 + (c2 - c1) / 10)) $cy 250; sleep 2
check "a sideways swipe on the cover plays the next song (B, the big .lrc)" test "$(song)" != "$before"
pause
# The 20,000-line .lrc: lyrics come within 20 s (only the first lines are parsed) and the app stays responsive.
wait_lyrics() { local i; for i in $(seq 20); do ui has lyricsChip && return 0; sleep 1; done; return 1; }
check "a 20,000-line .lrc still offers lyrics" wait_lyrics
check "its lyrics layer opens" eval 'ui tap lyricsChip && ui wait "Zeile 0" 8'
read -r W H < <(screen_size)
for i in 1 2 3 4 5; do a shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 120; sleep 0.4; done; still_muted
check "no app-not-responding after flinging through it" sh -c "! $ADB -s $SERIAL shell dumpsys activity processes | grep -q notResponding=true"
back; pause
a shell am force-stop "$PKG"
finish
