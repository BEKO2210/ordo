#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# The owner's rule: no word ever breaks in the middle ("Absp-ielen", "Zufäll-ig" on the Tab's narrow pane).
# Debug builds only (MidwordWatchProvider logs every visible TextView whose layout breaks inside a word); WRITES one
# silent test song with a cover in Music/OrdoE2E-Midword and removes it again. Walks home, settings, the music
# library's six tabs, a folder page, an album page, the player, its Sound and queue sheets, Ordo Musik's settings and
# (with ffmpeg on the host: a short test video with two chapters and a subtitle file beside the song) the video player,
# its lock, its speed sheet and its sheets Subtitles, Sound, Video info, Signal path, Chapters and Sleep timer, then
# fails on any logged break (plain BottomSheetDialogs are watched from Android 10).
# Run it once on a phone and once on a tablet.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/storage/emulated/0/Music/OrdoE2E-Midword
LIBRARY=me.zhanghai.android.files.viewer.audio.MusicLauncherActivity
if ! a shell dumpsys package "$PKG" | grep -q midword_watch; then
    echo "SKIP no-midword-breaks (not a debug build: the watcher is missing)"; exit 0
fi
tmp=$(mktemp -d); trap 'a shell rm -rf $DIR; rm -rf "$tmp"' EXIT
python3 - "$tmp/midword.mp3" <<'PY'
import struct, sys, zlib
def png(rgb, n=64):
    rows = b"".join(b"\0" + bytes(rgb) * n for _ in range(n))
    chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", n, n, 8, 2, 0, 0, 0)) + \
        chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")
def frame(name, data):
    return name.encode() + struct.pack(">I", len(data)) + b"\0\0" + data
text = lambda s: b"\0" + s.encode("latin-1")
body = frame("TIT2", text("Ordoe2e Midword Lied")) + frame("TPE1", text("Ordoe2e Ensemble")) + \
    frame("TALB", text("Ordoe2e Midword Album")) + frame("APIC", b"\0image/png\0\x03\0" + png((40, 90, 200)))
size = len(body)
tag = b"ID3\x03\0\0" + bytes([(size >> 21) & 127, (size >> 14) & 127, (size >> 7) & 127, size & 127]) + body
open(sys.argv[1], "wb").write(tag + (b"\xff\xfb\x90\x00" + bytes(413)) * (60 * 44100 // 1152))
PY
a shell "mkdir -p $DIR"; a push "$tmp/midword.mp3" "$DIR/midword.mp3" >/dev/null
a shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$DIR/midword.mp3" >/dev/null 2>&1
sleep 3
a logcat -c
launch
swipe_up; swipe_down
a shell am start -n "$PKG/me.zhanghai.android.files.settings.SettingsActivity" >/dev/null; sleep 3; guard
ui tap "Aussehen|Appearance" >/dev/null && sleep 2 && back
back
a shell am start -W -n "$PKG/$LIBRARY" >/dev/null; sleep 3; guard
for t in "Titel|Songs" "Alben|Albums" "Interpreten|Artists" "Wiedergabelisten|Playlists" "Favoriten|Favorites" \
    "Ordner|Folders"; do ui tap "$t" >/dev/null; sleep 2; done
check "a folder page opens" eval 'ui tap OrdoE2E-Midword title || { swipe_up; ui tap OrdoE2E-Midword title; }'
sleep 2; back
ui tap "Alben|Albums" >/dev/null; sleep 2
check "an album page opens" eval 'ui tap "Ordoe2e Midword Album" || { swipe_up; ui tap "Ordoe2e Midword Album"; }'
sleep 2
open_song() {
    ui tap "Ordoe2e Midword Lied" title || ui tap "Ordoe2e Midword Lied" || { swipe_up; ui tap "Ordoe2e Midword Lied"; } || return 1
    wait_on viewer.audio.AudioPlayerActivity 8
}
check "the player opens" open_song || { echo "DIAG:"; ui texts | head -15 | sed 's/^/  /'; }
# The wave flows while music plays and uiautomator waits for a still screen: pause first, by key.
sleep 2; a shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1
check "the Sound sheet opens" ui tap actionSound
sleep 2; swipe_up; swipe_up; sleep 1
back; sleep 1
check "the queue sheet opens" ui tap queueChip
sleep 2; back; back; sleep 2
# Ordo Musik's own settings (side menu): appearance with the colour choices.
ui tap "Navigationsmenü öffnen|Open navigation drawer" > /dev/null; sleep 1
check "Ordo Musik's settings open" eval 'ui tap "Einstellungen|Settings" && ui wait "Farben aus dem Cover|Colours from the cover" 5'
sleep 1; back; sleep 1
# The video player: controls, the locked screen's "hold to unlock", the speed sheet and the player's sheets.
if command -v ffmpeg >/dev/null; then
    # Two chapters, so the ⋮ menu offers "Chapters".
    printf ';FFMETADATA1\n' > "$tmp/chapters.txt"
    for c in "0 10000 Anfang" "10000 20000 Schluss"; do
        read -r from to title <<< "$c"
        printf '[CHAPTER]\nTIMEBASE=1/1000\nSTART=%s\nEND=%s\ntitle=%s\n' "$from" "$to" "$title" >> "$tmp/chapters.txt"
    done
    ffmpeg -v error -f lavfi -i "testsrc=size=640x360:rate=25:duration=20" -f lavfi -i "sine=frequency=440:duration=20" \
        -i "$tmp/chapters.txt" -map_metadata 2 -map_chapters 2 -map 0 -map 1 -c:v libx264 -pix_fmt yuv420p -c:a aac \
        -shortest "$tmp/midword-video.mp4"
    printf '1\n00:00:00,500 --> 00:00:19,000\nUntertitel ohne Wortbruch\n' > "$tmp/midword-video.de.srt"
    a push "$tmp/midword-video.mp4" "$tmp/midword-video.de.srt" "$DIR/" >/dev/null
    # Ordo Musik's paused session would keep the media keys (Tab S9, 21.11.2026: "Media button session is
    # androidx.media3.session.id.", the film played on to 0:13 and its controls stayed hidden): start the film alone.
    a shell am force-stop "$PKG"
    a shell am start -W -a android.intent.action.VIEW -d "file://$DIR" -t inode/directory \
        -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
    sleep 3; guard
    ui tap midword-video.mp4 >/dev/null
    check "the video player opens" wait_on viewer.video.VideoPlayerActivity 8
    sleep 2; a shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1
    check "the video player locks" eval 'ui tap lock && ui wait unlock 3'
    read -r l t r b < <(ui bounds unlock | head -1)
    a shell input swipe $(((l + r) / 2)) $(((t + b) / 2)) $(((l + r) / 2)) $(((t + b) / 2)) 900; sleep 1
    check "the video player's speed sheet opens" ui tap speed
    sleep 1; back 1
    # The controls hide while a film plays: a tap in the middle brings them back, then ⋮ › entry.
    menu() {
        read -r W H < <(screen_size)
        ui has "Weitere Optionen|More options" || { a shell input tap $((W / 2)) $((H / 2)); sleep 1; }
        ui tap "Weitere Optionen|More options" >/dev/null && sleep 1 && ui tap "$1" >/dev/null
    }
    check "the subtitles sheet opens" eval 'ui tap "Untertitel|Subtitles" >/dev/null || menu "Untertitel|Subtitles"'
    sleep 1.5; back 1
    for entry in "Klang|Sound" "Videoinfos|Video info" "Signalweg|Signal path" "Kapitel|Chapters" \
        "Schlaf-Timer|Sleep timer"; do
        check "the ${entry#*|} sheet opens" menu "$entry"
        sleep 1.5; back 1
    done
    back 2
else
    echo "SKIP the video player (no ffmpeg on the host)"
fi
hits=$(a logcat -d -s OrdoMidword:W | grep MIDWORD)
[ -n "$hits" ] && echo "$hits" | sed 's/.*MIDWORD/  MIDWORD/'
check "no visible text breaks inside a word" test -z "$hits"
a shell am force-stop "$PKG"
finish
