#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# farm: needs=shots timeout=1200
# Ordo Musik's library, WRITES: creates two folders with tagged 16-bit stereo test songs (three MP3, one WAV) and
# removes them afterwards. Opens the library in its own task, checks the tabs, the Songs list and search, plays a
# song, comes back to the library with the mini player, pauses from there, walks through the Folders and Albums tabs,
# and leaves the music app with back.
. "$(dirname "$0")/lib.sh" "$@"
ROOT=/storage/emulated/0
ROCK=$ROOT/Music/OrdoE2E-Rock
JAZZ=$ROOT/Download/OrdoE2E-Jazz
EDIT=$ROOT/Music/OrdoE2E-Edit
COVERS=$ROOT/Music/OrdoE2E-Covers
LIBRARY=me.zhanghai.android.files.viewer.audio.MusicLauncherActivity
PLAYLISTS=$ROOT/Music/Playlists
# The test's own playlist goes, and the favourites lose only the test songs (or go, if the test made them);
# the owner's lists stay as they were.
FAVORITES_BEFORE=$(a shell "[ -f $PLAYLISTS/Favorites.m3u8 ] && echo yes" | tr -d '\r')
clean_up() {
    a shell "rm -rf $ROCK $JAZZ $EDIT $COVERS; rm -f \"$PLAYLISTS/Ordoe2e List.m3u8\" \"$PLAYLISTS/Ordoe2eList.m3u8\""
    if [ "$FAVORITES_BEFORE" = yes ]; then
        a shell "sed -i /OrdoE2E-/d $PLAYLISTS/Favorites.m3u8"
    else
        a shell "rm -f $PLAYLISTS/Favorites.m3u8"
    fi
    rm -rf "$tmp"
}
tmp=$(mktemp -d); trap clean_up EXIT

# The albums get a name of their own per run: MediaStore must not mix them with songs of an earlier run.
RUN=$(date +%H%M%S)
ROCKS="Ordoe2e Rocks $RUN"
python3 - "$tmp" "$ROCKS" "Ordoe2e Jazz $RUN" <<'PY'
import pathlib, struct, sys, wave
root = pathlib.Path(sys.argv[1])

def id3(**frames):
    # ID3v2.3: frame sizes are plain big-endian, the tag size is syncsafe.
    body = b"".join(name.encode() + struct.pack(">I", len(text) + 1) + b"\0\0\0" + text.encode("latin-1")
                    for name, text in frames.items())
    size = len(body)
    return b"ID3\x03\0\0" + bytes([(size >> 21) & 127, (size >> 14) & 127, (size >> 7) & 127, size & 127]) + body

# Silent MPEG-1 Layer III frames, 128 kbit/s, 44.1 kHz, stereo: 417 bytes each, 30 s of them.
frame = b"\xff\xfb\x90\x00" + bytes(413)
silence = frame * (30 * 44100 // 1152)
for name, title, artist, album, track in [
    ("rock-1.mp3", "Ordoe2e Alpha", "Ordoe2e Band", sys.argv[2], "1"),
    ("rock-2.mp3", "Ordoe2e Beta", "Ordoe2e Band", sys.argv[2], "2"),
    ("jazz-1.mp3", "Ordoe2e Gamma", "Ordoe2e Duo", sys.argv[3], "1"),
]:
    (root / name).write_bytes(id3(TIT2=title, TPE1=artist, TALB=album, TRCK=track) + silence)
# Two songs without an album in one folder (MediaStore makes them one album, named after the folder), each with a
# cover of its own embedded: red and blue. The folder page must show each song's cover, not one for all.
import zlib
def png(rgb, n=64):
    rows = b"".join(b"\0" + bytes(rgb) * n for _ in range(n))
    chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", n, n, 8, 2, 0, 0, 0)) + \
        chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")
def apic(name, data):
    return name.encode() + struct.pack(">I", len(data)) + b"\0\0" + data
for name, title, rgb in [("cover-red.mp3", "Ordoe2e Red", (220, 30, 30)), ("cover-blue.mp3", "Ordoe2e Blue", (30, 60, 220))]:
    body = b"".join(apic(k, b"\0" + v.encode()) for k, v in (("TIT2", title), ("TPE1", "Ordoe2e Duo"))) + \
        apic("APIC", b"\0image/png\0\x03\0" + png(rgb))
    size = len(body)
    tag = b"ID3\x03\0\0" + bytes([(size >> 21) & 127, (size >> 14) & 127, (size >> 7) & 127, size & 127]) + body
    (root / name).write_bytes(tag + silence)
# A WAV without tags, 16-bit stereo like real music: its title comes from the file name.
with wave.open(str(root / "Ordoe2e Delta.wav"), "wb") as out:
    out.setnchannels(2); out.setsampwidth(2); out.setframerate(44100)
    out.writeframes(struct.pack("<hh", 24, -24) * (44100 * 30))
PY
a shell "rm -rf $ROCK $JAZZ; mkdir -p $ROCK $JAZZ"
a push "$tmp/rock-1.mp3" "$tmp/rock-2.mp3" "$ROCK/" >/dev/null
a push "$tmp/jazz-1.mp3" "$tmp/Ordoe2e Delta.wav" "$JAZZ/" >/dev/null
a shell "rm -rf $COVERS; mkdir -p $COVERS"
a push "$tmp/cover-red.mp3" "$tmp/cover-blue.mp3" "$COVERS/" >/dev/null
for f in cover-red.mp3 cover-blue.mp3; do
    a shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$COVERS/$f" >/dev/null 2>&1
done
# The tag editor's test songs: keep their streams but give the two fixtures distinct seven-byte titles. Replacing
# equal-length metadata leaves both ID3 and Vorbis-comment block sizes intact and makes the selected format explicit.
a shell "rm -rf $EDIT; mkdir -p $EDIT"
python3 - "$HERE/../app/src/test/resources/tags" "$tmp" <<'PY'
import pathlib, sys
source, target = map(pathlib.Path, sys.argv[1:])
for name, title in (("song.mp3", b"Mp3Test"), ("song.flac", b"FlacTag")):
    data = (source / name).read_bytes()
    if data.count(b"Fixture") != 1 or len(title) != len(b"Fixture"):
        raise SystemExit(f"unexpected tag fixture: {name}")
    (target / ("edit-me" + pathlib.Path(name).suffix)).write_bytes(data.replace(b"Fixture", title))
PY
a push "$tmp/edit-me.mp3" "$EDIT/edit-me.mp3" >/dev/null
a push "$tmp/edit-me.flac" "$EDIT/edit-me.flac" >/dev/null
a shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$EDIT/edit-me.mp3" >/dev/null 2>&1
a shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$EDIT/edit-me.flac" >/dev/null 2>&1
# adb push leaves new files pending in MediaStore (no tags, not counted as music) until a scan: ask for one per file.
for f in "$ROCK/rock-1.mp3" "$ROCK/rock-2.mp3" "$JAZZ/jazz-1.mp3" "$JAZZ/Ordoe2e Delta.wav"; do
    a shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://${f// /%20}" >/dev/null 2>&1
done
indexed() {
    local i; for i in $(seq 20); do
        [ "$(a shell content query --uri content://media/external/audio/media --projection title \
            --where "\"_data LIKE '%/OrdoE2E-%' AND is_music=1\"" 2>/dev/null | grep -c '^Row')" -ge 4 ] && return 0
        sleep 1
    done
    return 1
}
check "MediaStore indexed the four test songs" indexed

wake; grant; a logcat -b crash -c
a shell am force-stop "$PKG"
a shell am start -W -n "$PKG/$LIBRARY" >/dev/null
check_or_abort "Ordo Musik opens its library, not the player" wait_on viewer.audio.MusicLauncherActivity 5; guard
# Ordo Musik is its own app: a task of its own affinity, a separate card in Recents next to the file manager.
music_task() { a shell dumpsys activity recents | grep -Eq "A=[0-9]+:me\.zhanghai\.android\.files\.music([ }]|$)"; }
check "Recents shows Ordo Musik as a task of its own" music_task
# On a phone the six tabs scroll sideways: a tab off screen is swiped into view (towards the end, then back).
show_tab() {
    local i x1 y1 x2 y2 dir l r y
    ui has "$1" && return 0
    for dir in end start end start; do
        read -r x1 y1 x2 y2 < <(ui bounds tabs | head -1); [ -n "$y2" ] || return 1
        # Inside the middle half: a swipe from the screen's edge is the system back gesture.
        l=$((x1 + (x2 - x1) / 4)); r=$((x2 - (x2 - x1) / 4)); y=$(((y1 + y2) / 2))
        for i in 1 2; do
            if [ $dir = end ]; then a shell input swipe $r $y $l $y 300; else a shell input swipe $l $y $r $y 300; fi
            ui wait "$1" 1.5 >/dev/null 2>&1 && return 0
        done
    done
    return 1
}
for t in "Titel|Songs" "Alben|Albums" "Interpreten|Artists" "Wiedergabelisten|Playlists" "Favoriten|Favorites" \
    "Ordner|Folders"; do
    check "the tab $t is shown" show_tab "$t"
done
show_tab "Titel|Songs" >/dev/null
check "shuffle all is the first row" eval 'ui texts | grep -Eq "^(Alle zufällig|Shuffle all) \([0-9]+\)\|"'
# A device has other music too: every list is narrowed by search to the test songs, which no other name contains.
# Enter submits the search: the soft keyboard closes and no longer hides the lower rows (phones).
search() { ui tap actionSearch >/dev/null && sleep 1 && a shell input text "${1// /%s}" && a shell input keyevent KEYCODE_ENTER && sleep 2; }
# The first back may only hide the keyboard; back is pressed once more only while the search field is still there.
# Back only while the search field is there: one more back would leave the app.
close_search() {
    ui has search_src_text || return 0
    back 1; ui has search_src_text || return 0; back 1; ! ui has search_src_text
}
above() { local a b; a=$(ui bounds "$1" | head -1 | cut -d" " -f2); b=$(ui bounds "$2" | head -1 | cut -d" " -f2); [ "$a" -lt "$b" ]; }
check "search opens from the toolbar" search Ordoe2e
# On a landscape phone only two or three song rows fit: a row is scrolled into sight in the song list (towards its
# end, then back), never with a swipe over the tabs.
row_shown() {
    local dir i
    ui has "$@" && return 0
    for dir in up down; do for i in 1 2 3 4 5 6; do scroll_in $dir title || break; ui has "$@" && return 0; done; done
    return 1
}
# A before B from the list's top down: the titles are read page by page while the list scrolls, in screen order.
listed_before() {
    local i
    row_shown "$1" title || return 1
    : > "$tmp/titles"
    for i in 1 2 3 4 5 6; do
        ui texts | grep '|title|$' | cut -d'|' -f1 >> "$tmp/titles"
        grep -qxF "$2" "$tmp/titles" && break
        scroll_in up title || break
    done
    awk -v a="$1" -v b="$2" '$0 == a && !x { x = NR } $0 == b && !y { y = NR } END { exit !(x && y && x < y) }' "$tmp/titles"
}
check "Songs lists the tagged titles" ui wait "Ordoe2e Alpha" 10
check "the second title is listed" row_shown "Ordoe2e Beta" title
check "the third title is listed" row_shown "Ordoe2e Gamma" title
check "an untagged WAV is listed by its file name" row_shown "Ordoe2e Delta" title
check "songs are sorted by title" listed_before "Ordoe2e Alpha" "Ordoe2e Beta"
row_shown "Ordoe2e Alpha" title >/dev/null

session_state() { a shell dumpsys media_session | grep -A25 "package=$PKG" | grep -q "state=$1"; }
playing() { local i; for i in $(seq 10); do session_state PLAYING && return 0; sleep 1; done; return 1; }
check "tapping a song opens the player" ui tap "Ordoe2e Alpha" title
check_or_abort "the player is in front" wait_on viewer.audio.AudioPlayerActivity 8
check_or_abort "the song plays" playing
back 2
check "back returns to the library" on viewer.audio.MusicLauncherActivity
mini() { local i; for i in 1 2 3 4 5; do ui visible miniPlayer && return 0; sleep 1; done; return 1; }
check "the mini player is shown" mini
check "the mini player names the song" eval 'ui texts | grep -Eq "\|miniPlayer\|(Läuft gerade|Now playing): Ordoe2e Alpha"'
check "the mini player pauses" ui tap playPause
paused() { local i; for i in 1 2 3 4 5; do session_state PAUSED && return 0; sleep 1; done; return 1; }
check "playback is paused" paused
# Tapping the song that is already playing (here paused) opens the player without a restart: the position stays.
position() {
    a shell dumpsys media_session | grep -A25 "package=$PKG" | grep -m1 "state=PlaybackState" \
        | grep -o "[^d] position=[0-9]*" | head -1 | cut -d= -f2
}
same_song_keeps_position() {
    local before after
    before=$(position); [ "${before:-0}" -gt 1000 ] || return 1
    row_shown "Ordoe2e Alpha" title && ui tap "Ordoe2e Alpha" title || return 1
    wait_on viewer.audio.AudioPlayerActivity 8 || return 1
    sleep 2; after=$(position); back 2
    echo "position before $before ms, after $after ms" > "$tmp/position.txt"
    [ "${after:-0}" -ge "$before" ] && [ $((after - before)) -lt 1500 ]
}
check "tapping the playing song keeps its position" same_song_keeps_position
cat "$tmp/position.txt" 2>/dev/null
# Every song row has its ⋮; while a queue exists it offers play next and add to queue besides showing the file.
check_or_abort "a song row opens its options" eval 'row_shown "Weitere Optionen für Ordoe2e Beta|More options for Ordoe2e Beta" && ui tap "Weitere Optionen für Ordoe2e Beta|More options for Ordoe2e Beta" && ui wait "Als Nächstes spielen|Play next" 5'
pass "options offer play next"
check "options offer add to queue" ui has "Zur Warteschlange|Add to queue"
# On a landscape phone the popup shows four rows: the last one is scrolled into sight within the popup's list.
check "options offer showing the file" scroll_to "Im Ordner zeigen|Show in folder" 3 "Zur Warteschlange|Add to queue"
back 1
check "closing search keeps the library open" close_search
check "the library is still in front" on viewer.audio.MusicLauncherActivity

# Ordo's side menu: the song played above is in the play history; Sound opens over the player.
MENU="Navigationsmenü öffnen|Open navigation drawer"
# From 840 dp width (a tablet in landscape, a Tab S9 Ultra even upright) the menu is a pane that is always there;
# the button would close it. Otherwise the button slides it in.
open_menu() { ui has "Bibliothek|Library" titleText && return 0; ui tap "$MENU" && sleep 1; }
# The menu's entries by their id: Playlists and Favourites are also tabs, still in the screen dump behind the menu.
check "the side menu is open or opens" open_menu
check "the side menu names Ordo Musik" ui wait "Ordo Musik|Ordo Music" 5
check "the side menu offers the library" ui has "Bibliothek|Library"
# On a landscape phone the menu's list is taller than its window: an entry is swiped into sight within that list
# (its rows are titleText), towards its end, then back to its top.
nav_row() {
    local dir i
    ui has "$1" titleText && return 0
    for dir in up down; do for i in 1 2 3 4; do scroll_in $dir titleText || break; ui has "$1" titleText && return 0; done; done
    return 1
}
check "the side menu offers sound and the sleep timer" nav_row "Einschlaf-Timer|Sleep timer"
check_or_abort "recently played opens with the played song" eval 'nav_row "Zuletzt gespielt|Recently played" && ui tap "Zuletzt gespielt|Recently played" && ui wait "Ordoe2e Alpha" 5'
check_or_abort "the menu goes back to the library" eval 'open_menu && ui tap "Bibliothek|Library" && ui wait "Alben|Albums" 5'
pass "the library tabs are back"
check_or_abort "Sound opens from the side menu" eval 'open_menu && nav_row "Klang|Sound" && ui tap "Klang|Sound" titleText && ui wait "Equalizer" 8'
pass "the Sound sheet opens over the player"
back 1.5; back 2
check "back from the player returns to the library" on viewer.audio.MusicLauncherActivity

# Selection, playlists and favourites: a long press selects, the songs go into a new M3U8 playlist in
# Music/Playlists, the playlist and the favourites show them in the side menu.
# The search also finds the tag editor's songs (folder OrdoE2E-Edit) and sorts them first: on a landscape phone Alpha
# lies below the window (A15, rc1b), so it is scrolled into sight before the long press.
check "a long press selects a song" eval 'search Ordoe2e && row_shown "Ordoe2e Alpha" title && ui longtap "Ordoe2e Alpha" title && ui wait "1 ausgewählt|1 selected" 5'
# On failure the log shows what the toolbar showed instead.
ui has "1 ausgewählt|1 selected" || { echo "DIAG selection:"; ui texts | head -20 | sed 's/^/  /'; }
# Scrolled into sight first: on the W90 the selection showed the whole library, Beta below the window (a15g-5).
check "a tap adds a second song" eval 'row_shown "Ordoe2e Beta" title && ui tap "Ordoe2e Beta" title && ui wait "2 ausgewählt|2 selected" 5'
# The keyboard is hidden before Save: on a landscape tablet it covered the dialog's buttons, and the tap on Save
# picked the keyboard's suggestion "Liste" instead (W90, a15g-6). Escape hides it (as in backup-restore.sh); a back
# press could close the dialog too.
kb_hidden() { a shell input keyevent 111; sleep 0.8; }
check_or_abort "the selection goes into a new playlist" eval 'ui tap actionSelectionPlaylist && ui wait "Neue Wiedergabeliste|New playlist" 5 \
    && ui tap "Neue Wiedergabeliste|New playlist" && sleep 1 && a shell input text Ordoe2eList && kb_hidden \
    && ui has "Neue Wiedergabeliste|New playlist" alertTitle && ui tap button1'
in_playlist() { a shell cat "\"$PLAYLISTS/Ordoe2eList.m3u8\"" | grep -q "OrdoE2E-Rock/rock-1.mp3"; }
check "the playlist is an M3U8 file in Music/Playlists" eval 'sleep 2; in_playlist'
close_search
ui has "$MENU" || ui has "Bibliothek|Library" titleText || { echo "DIAG before the side menu:"; ui texts | head -20 | sed 's/^/  /'; }
check_or_abort "the side menu lists the playlist" eval 'open_menu && nav_row "Wiedergabelisten|Playlists" && ui tap "Wiedergabelisten|Playlists" titleText && ui wait Ordoe2eList 5'
# As song rows (id title): the mini player names Alpha too, and on a landscape phone the rows start below the
# playlist's header (A15, rc1d).
# No song row may be on screen yet, so the page's own scroller moves (row_shown anchors on a row).
page_row() { local i; for i in 1 2 3 4 5 6; do ui has "$1" title && return 0; scroll_in up || return 1; done; ui has "$1" title; }
check_or_abort "the playlist opens with its songs" eval 'ui tap Ordoe2eList && ui wait "Ordoe2e Alpha" 5 && page_row "Ordoe2e Alpha" && page_row "Ordoe2e Beta"'
back 1.5; back 1.5
# Gamma's row scrolled into sight: on a landscape tablet the results start below the menu (W90, a15g-7).
check "a song becomes a favourite from its menu" eval 'search Ordoe2e && row_shown "Weitere Optionen für Ordoe2e Gamma|More options for Ordoe2e Gamma" \
    && ui tap "Weitere Optionen für Ordoe2e Gamma|More options for Ordoe2e Gamma" \
    && ui wait "Zu Favoriten|Add to favorites" 5 && ui tap "Zu Favoriten|Add to favorites"'
close_search
check_or_abort "the favourites show it" eval 'open_menu && nav_row "Favoriten|Favorites" && ui tap "Favoriten|Favorites" titleText && ui wait "Ordoe2e Gamma" 5'
# Favourites and playlists are tabs of the library: the tab row stays, the selected tab scrolled into view (phones).
check "the favourites keep the tab row, their tab in view" ui has "Favoriten|Favorites"
# The menu still stands at Favourites: on a landscape phone Library is scrolled off its top (A15, rc1d).
# A second first: the menu that opened Favourites may still be sliding shut, and open_menu then took it for open.
check "the menu returns to the library" eval 'sleep 1; open_menu && nav_row "Bibliothek|Library" && ui tap "Bibliothek|Library" && ui wait "Alben|Albums" 5'

# The tag editor: a new title for the MP3 and the FLAC; the library shows it and the song still plays.
# A field of the editor's page, scrolled to downwards, then upwards (the page may keep its scroll across a rotation).
field_shown() {
    scroll_to "$1" 4 && return 0
    local i; for i in 1 2 3 4; do scroll_in down || return 1; ui has "$1" && return 0; done; return 1
}
edit_title() {
    local old=$1 new=$2
    search "$old" || return 1
    ui tap "Weitere Optionen für $old|More options for $old" || return 1
    ui wait "Infos bearbeiten|Edit info" 5 && ui tap "Infos bearbeiten|Edit info" || return 1
    wait_on viewer.audio.MusicTagEditorActivity 8 || return 1
    ui wait "$old" 8 && ui tap "$old" || return 1
    a shell input keycombination 113 29; sleep 0.5   # Ctrl+A: the new title replaces the old one
    a shell input text "${new// /%s}"; sleep 1 # deliberate: the search waits for the end of typing (debounce) and then loads
    a shell input keyevent KEYCODE_BACK; sleep 1      # hide the keyboard
    local old_auto old_rotation next_rotation
    old_auto=$(a shell settings get system accelerometer_rotation | tr -d '\r')
    old_rotation=$(a shell settings get system user_rotation | tr -d '\r')
    next_rotation=$([ "${old_rotation:-0}" = 0 ] && echo 1 || echo 0)
    a shell settings put system accelerometer_rotation 0
    a shell settings put system user_rotation "$next_rotation"; sleep 3
    local kept=0
    # A landscape phone shows the cover and file above the fields: the title field may lie below the screen.
    wait_on viewer.audio.MusicTagEditorActivity 5 && field_shown "$new" && kept=1
    a shell settings put system user_rotation "${old_rotation:-0}"
    a shell settings put system accelerometer_rotation "${old_auto:-1}"; sleep 3
    [ $kept = 1 ] && wait_on viewer.audio.MusicTagEditorActivity 5 && field_shown "$new" || return 1
    ui tap actionSave || return 1                     # the toolbar's Save (shown in capitals) opens the preview
    ui wait "Diese Änderungen speichern?|Save these changes?" 5 || return 1
    ui tap button1 || return 1                        # the preview's Save
    wait_on viewer.audio.MusicLauncherActivity 10
}
# As a song row (id title): the search field itself holds the same text and made this pass on an empty result (a15g-6a).
listed() { local i; for i in 1 2 3 4 5 6; do close_search >/dev/null 2>&1; search "$1" && ui has "$1" title && return 0; sleep 2; done; return 1; }
check_or_abort "the MP3 gets a new title in the editor" edit_title Mp3Test "Ordoe2e Edited Mp"
check "Undo is offered after saving" ui wait "Rückgängig|Undo" 5
check "the library shows the edited MP3" listed "Ordoe2e Edited Mp"
# On failure the log shows what MediaStore holds for the edited song (title, is_music) and what the list showed.
ui has "Ordoe2e Edited Mp" title || {
    echo "DIAG edited MP3:"
    a shell content query --uri content://media/external/audio/media --projection _data:title:is_music:date_modified \
        --where "\"_data LIKE '%/OrdoE2E-Edit/%'\"" | sed 's/^/  /'
    a shell ls -la "$EDIT" | sed 's/^/  /'
    ui texts | head -20 | sed 's/^/  /'
}
check_or_abort "the edited MP3 opens the player" eval 'ui tap "Ordoe2e Edited Mp" title && wait_on viewer.audio.AudioPlayerActivity 8'
check_or_abort "the edited MP3 plays" playing
back 2
close_search
check_or_abort "the FLAC gets a new title in the editor" edit_title FlacTag "Ordoe2e Edited Flac"
check "the library shows the edited FLAC" listed "Ordoe2e Edited Flac"
check_or_abort "the edited FLAC opens the player" eval 'ui tap "Ordoe2e Edited Flac" title && wait_on viewer.audio.AudioPlayerActivity 8'
check_or_abort "the edited FLAC plays" playing
back 2
close_search

# Each song on a folder page shows its own cover: the red and the blue song's covers differ on screen.
cover_colour() { # average colour of the 48 dp cover left of the row's title
    local x1 y1 x2 y2 dpi
    read -r x1 y1 x2 y2 < <(ui bounds "$1" title | head -1) || return 1
    dpi=$(a shell wm density | tail -1 | sed 's/.*: //' | tr -d '\r')
    screenshot "$tmp/covers.png" >/dev/null || return 1
    python3 - "$tmp/covers.png" "$x1" "$y1" "$y2" "$dpi" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
x1, y1, y2, dpi = (int(v) for v in sys.argv[2:6])
dp = dpi / 160
right = int(x1 - 13 * dp - 6 * dp); left = int(right - 36 * dp); cy = (y1 + y2) // 2
box = im.crop((left, cy - int(12 * dp), right, cy + int(12 * dp)))
px = list(box.getdata()); n = len(px)
print(*(sum(p[k] for p in px) // n for k in range(3)))
PY
}
own_covers() {
    local red blue
    show_tab "Ordner|Folders" && ui tap "Ordner|Folders" && search OrdoE2E-Covers && ui tap OrdoE2E-Covers title && ui wait "Ordoe2e Red" 5 || return 1
    sleep 2
    red=$(cover_colour "Ordoe2e Red"); blue=$(cover_colour "Ordoe2e Blue")
    echo "covers: red row $red, blue row $blue" > "$tmp/covers.txt"
    read -r rr rg rb <<< "$red"; read -r br bg bb <<< "$blue"
    back 1.5; close_search
    [ "$rr" -gt "$rb" ] && [ "$bb" -gt "$br" ]
}
check_or_abort "a folder page shows each song's own cover" own_covers
cat "$tmp/covers.txt" 2>/dev/null
check "the Folders tab opens" eval 'show_tab "Ordner|Folders" && ui tap "Ordner|Folders"'
search OrdoE2E
check "the first folder is listed" ui wait OrdoE2E-Rock 5
check "the second folder is listed" ui has OrdoE2E-Jazz
check_or_abort "a folder opens its songs" eval 'ui tap OrdoE2E-Jazz && ui wait "Ordoe2e Gamma" 5'
pass "the folder lists its songs"
check "the folder lists its WAV" ui has "Ordoe2e Delta"
check "the folder offers play and shuffle" ui has "Abspielen|Play"
back 1.5
check "back leaves the folder, not the app" on viewer.audio.MusicLauncherActivity
check "the tabs are back" show_tab "Alben|Albums"
check "the Albums tab opens" ui tap "Alben|Albums"
search Ordoe2e
check "the album is listed" ui wait "$ROCKS" 5
check_or_abort "an album opens its songs" eval 'ui tap "$ROCKS" && ui wait "Ordoe2e Alpha" 5'
pass "the album's first track is shown"
check "tracks keep the album's order" above "Ordoe2e Alpha" "Ordoe2e Beta"
# Only song rows carry the id "title" (the mini player and the menu's "now playing" have their own): they count.
gamma_rows() { ui texts | grep -c '^Ordoe2e Gamma|title|'; }
if [ "$(gamma_rows)" -gt 0 ]; then
    fail "the album leaves out other songs"
    # What the page showed and how MediaStore groups the test songs, for the log.
    echo "DIAG album page:"; ui texts | sed 's/^/  /'
    echo "DIAG MediaStore:"; a shell content query --uri content://media/external/audio/media \
        --projection _data:album:album_id:album_artist:title --where "\"_data LIKE '%OrdoE2E-%'\"" | sed 's/^/  /'
else
    pass "the album leaves out other songs"
fi
back 1.5
check "back closes the album" ui has "Alben|Albums"
back 2
not_in_ordo() { ! top | grep -q "^$PKG/"; }
check "back from the library leaves the music app, not into the file manager" not_in_ordo
a shell am force-stop "$PKG"
finish
