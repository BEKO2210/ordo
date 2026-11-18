#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Ordo Musik's library, WRITES: creates two folders with tagged 16-bit stereo test songs (three MP3, one WAV) and
# removes them afterwards. Opens the library in its own task, checks the tabs, the Songs list and search, plays a
# song, comes back to the library with the mini player, pauses from there, walks through the Folders and Albums tabs,
# and leaves the music app with back.
. "$(dirname "$0")/lib.sh" "$@"
ROOT=/storage/emulated/0
ROCK=$ROOT/Music/OrdoE2E-Rock
JAZZ=$ROOT/Download/OrdoE2E-Jazz
EDIT=$ROOT/Music/OrdoE2E-Edit
LIBRARY=me.zhanghai.android.files.viewer.audio.MusicLauncherActivity
tmp=$(mktemp -d); trap 'a shell "rm -rf $ROCK $JAZZ $EDIT"; rm -rf "$tmp"' EXIT

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
# A WAV without tags, 16-bit stereo like real music: its title comes from the file name.
with wave.open(str(root / "Ordoe2e Delta.wav"), "wb") as out:
    out.setnchannels(2); out.setsampwidth(2); out.setframerate(44100)
    out.writeframes(struct.pack("<hh", 24, -24) * (44100 * 30))
PY
a shell "rm -rf $ROCK $JAZZ; mkdir -p $ROCK $JAZZ"
a push "$tmp/rock-1.mp3" "$tmp/rock-2.mp3" "$ROCK/" >/dev/null
a push "$tmp/jazz-1.mp3" "$tmp/Ordoe2e Delta.wav" "$JAZZ/" >/dev/null
# The tag editor's test songs: the unit tests' 1 s stereo MP3 and FLAC, both titled "Fixture".
a shell "rm -rf $EDIT; mkdir -p $EDIT"
a push "$HERE/../app/src/test/resources/tags/song.mp3" "$EDIT/edit-me.mp3" >/dev/null
a push "$HERE/../app/src/test/resources/tags/song.flac" "$EDIT/edit-me.flac" >/dev/null
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
a shell am start -W -n "$PKG/$LIBRARY" >/dev/null; sleep 3; guard
check "Ordo Musik opens its library, not the player" on viewer.audio.MusicLauncherActivity
# Ordo Musik is its own app: a task of its own affinity, a separate card in Recents next to the file manager.
music_task() { a shell dumpsys activity recents | grep -Eq "A=[0-9]+:me\.zhanghai\.android\.files\.music([ }]|$)"; }
check "Recents shows Ordo Musik as a task of its own" music_task
for t in "Titel|Songs" "Alben|Albums" "Interpreten|Artists" "Ordner|Folders"; do
    check "the tab $t is shown" ui has "$t"
done
check "shuffle all is the first row" eval 'ui texts | grep -Eq "^(Alle zufällig|Shuffle all) \([0-9]+\)\|"'
# A device has other music too: every list is narrowed by search to the test songs, which no other name contains.
# Enter submits the search: the soft keyboard closes and no longer hides the lower rows (phones).
search() { ui tap actionSearch >/dev/null && sleep 1 && a shell input text "$1" && a shell input keyevent KEYCODE_ENTER && sleep 2; }
# The first back may only hide the keyboard; back is pressed once more only while the search field is still there.
close_search() { back 1; ui has search_src_text || return 0; back 1; ! ui has search_src_text; }
above() { local a b; a=$(ui bounds "$1" | head -1 | cut -d" " -f2); b=$(ui bounds "$2" | head -1 | cut -d" " -f2); [ "$a" -lt "$b" ]; }
check "search opens from the toolbar" search Ordoe2e
check "Songs lists the tagged titles" ui wait "Ordoe2e Alpha" 10
check "the second title is listed" ui has "Ordoe2e Beta"
check "the third title is listed" ui has "Ordoe2e Gamma"
check "an untagged WAV is listed by its file name" ui has "Ordoe2e Delta"
check "songs are sorted by title" above "Ordoe2e Alpha" "Ordoe2e Beta"

session_state() { a shell dumpsys media_session | grep -A25 "package=$PKG" | grep -q "state=$1"; }
playing() { local i; for i in $(seq 10); do session_state PLAYING && return 0; sleep 1; done; return 1; }
check "tapping a song opens the player" ui tap "Ordoe2e Alpha"
check "the player is in front" wait_on viewer.audio.AudioPlayerActivity 8
check "the song plays" playing
back 2
check "back returns to the library" on viewer.audio.MusicLauncherActivity
mini() { local i; for i in 1 2 3 4 5; do ui visible miniPlayer && return 0; sleep 1; done; return 1; }
check "the mini player is shown" mini
check "the mini player names the song" eval 'ui texts | grep -Eq "\|miniPlayer\|(Läuft gerade|Now playing): Ordoe2e Alpha"'
check "the mini player pauses" ui tap playPause
paused() { local i; for i in 1 2 3 4 5; do session_state PAUSED && return 0; sleep 1; done; return 1; }
check "playback is paused" paused
# Every song row has its ⋮; while a queue exists it offers play next and add to queue besides showing the file.
check "a song row has its options button" ui tap "Weitere Optionen für Ordoe2e Beta|More options for Ordoe2e Beta"
check "options offer play next" ui wait "Als Nächstes spielen|Play next" 5
check "options offer add to queue" ui has "Zur Warteschlange|Add to queue"
check "options offer showing the file" ui has "Datei anzeigen|Show file"
back 1
check "closing search keeps the library open" close_search
check "the library is still in front" on viewer.audio.MusicLauncherActivity

# Ordo's side menu: the song played above is in the play history; Sound opens over the player.
check "the menu button opens the side menu" ui tap "Navigationsmenü öffnen|Open navigation drawer"
check "the side menu names Ordo Musik" ui wait "Ordo Musik|Ordo Music" 5
check "the side menu offers the library" ui has "Bibliothek|Library"
check "the side menu offers sound and the sleep timer" ui has "Einschlaf-Timer|Sleep timer"
check "recently played opens" ui tap "Zuletzt gespielt|Recently played"
check "the played song is in the history" ui wait "Ordoe2e Alpha" 5
check "the menu goes back to the library" eval 'ui tap "Navigationsmenü öffnen|Open navigation drawer" && sleep 1 && ui tap "Bibliothek|Library"'
check "the library tabs are back" ui wait "Ordner|Folders" 5
check "Sound opens from the side menu" eval 'ui tap "Navigationsmenü öffnen|Open navigation drawer" && sleep 1 && ui tap "Klang|Sound"'
check "the Sound sheet opens over the player" ui wait "Equalizer" 8
back 1.5; back 2
check "back from the player returns to the library" on viewer.audio.MusicLauncherActivity

# The tag editor: a new title for the MP3 and the FLAC; the library shows it and the song still plays.
edit_title() {
    local new=$1
    search Fixture || return 1
    ui tap "Weitere Optionen für Fixture|More options for Fixture" || return 1
    ui wait "Infos bearbeiten|Edit info" 5 && ui tap "Infos bearbeiten|Edit info" || return 1
    wait_on viewer.audio.MusicTagEditorActivity 8 || return 1
    ui wait Fixture 8 && ui tap Fixture || return 1
    a shell input keycombination 113 29; sleep 0.5   # Ctrl+A: the new title replaces the old one
    a shell input text "${new// /%s}"; sleep 1
    a shell input keyevent KEYCODE_BACK; sleep 1      # hide the keyboard
    ui tap actionSave || return 1                     # the toolbar's Save (shown in capitals) opens the preview
    ui wait "Diese Änderungen speichern?|Save these changes?" 5 || return 1
    ui tap button1 || return 1                        # the preview's Save
    wait_on viewer.audio.MusicLauncherActivity 10
}
listed() { local i; for i in 1 2 3 4 5 6; do close_search >/dev/null 2>&1; search "$1" && ui has "$1" && return 0; sleep 2; done; return 1; }
check "the MP3 gets a new title in the editor" edit_title "Ordoe2e Edited Mp"
check "Undo is offered after saving" ui wait "Rückgängig|Undo" 5
check "the library shows the edited MP3" listed "Ordoe2e Edited Mp"
check "the edited MP3 opens the player" ui tap "Ordoe2e Edited Mp"
check "the edited MP3 plays" playing
back 2
close_search
check "the FLAC gets a new title in the editor" edit_title "Ordoe2e Edited Flac"
check "the library shows the edited FLAC" listed "Ordoe2e Edited Flac"
check "the edited FLAC opens the player" ui tap "Ordoe2e Edited Flac"
check "the edited FLAC plays" playing
back 2
close_search

check "the Folders tab opens" ui tap "Ordner|Folders"
search OrdoE2E
check "the first folder is listed" ui wait OrdoE2E-Rock 5
check "the second folder is listed" ui has OrdoE2E-Jazz
check "a folder opens its songs" ui tap OrdoE2E-Jazz
check "the folder lists its songs" ui wait "Ordoe2e Gamma" 5
check "the folder lists its WAV" ui has "Ordoe2e Delta"
check "the folder offers play and shuffle" ui has "Abspielen|Play"
back 1.5
check "back leaves the folder, not the app" on viewer.audio.MusicLauncherActivity
check "the tabs are back" ui has "Alben|Albums"
check "the Albums tab opens" ui tap "Alben|Albums"
search Ordoe2e
check "the album is listed" ui wait "$ROCKS" 5
check "an album opens its songs" ui tap "$ROCKS"
check "the album's first track is shown" ui wait "Ordoe2e Alpha" 5
check "tracks keep the album's order" above "Ordoe2e Alpha" "Ordoe2e Beta"
if ui has "Ordoe2e Gamma"; then
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
