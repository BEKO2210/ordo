#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Ordo Video's library pro, WRITES: creates /storage/emulated/0/ordo-e2e-serie (Dark/Staffel 1 with S01E01–S01E03,
# 12-s clips made by ffmpeg on the host, plus a 41-min still "Der lange Film.mp4") and removes it again, with what
# it sent to the trash. The library shows the shelves "Serien" and "Filme"; the series card opens its page with
# "Staffel 1" and the episodes in order; the search finds the series by a word of its name and an empty result says
# so; a long press selects (title "1 ausgewählt"), "Als gesehen markieren" puts a full bar on the card and "Nur
# ungesehene" hides it; with S01E01 watched, "Weiterschauen" offers the next episode ("Als Nächstes · S1 F2"); played
# with "Automatisch weiter", S01E02 goes on to S01E03 (the season is the queue); "In den Papierkorb" after its
# question moves a video into .OrdoTrash. Tresor playback needs the biometric prompt: SKIP, by hand (put a video in
# the Tresor, unlock, tap it: it plays in Ordo's player, no file in cache/vault-open, no screenshot possible).
. "$(dirname "$0")/lib.sh" "$@"
ROOT=/storage/emulated/0/ordo-e2e-serie
DIR=$ROOT/Dark/Staffel\ 1
tmp=$(mktemp -d); trap 'a shell "rm -rf $ROOT"; trash_drop ordo-e2e-serie; rm -rf "$tmp"' EXIT
command -v ffmpeg >/dev/null || { echo "SKIP video library (no ffmpeg on the host)"; exit 0; }

for n in 1 2 3; do
    ffmpeg -v error -f lavfi -i "testsrc=size=640x360:rate=25:duration=12" -f lavfi -i "sine=frequency=$((300 + n * 100)):duration=12" \
        -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -shortest "$tmp/Dark.S01E0$n.mp4" || echo "FAIL ffmpeg episode $n"
done
# 41 minutes of one still frame: tiny, but long enough to count as a film.
ffmpeg -v error -f lavfi -i "color=c=navy:size=320x180:rate=1:duration=2460" -c:v libx264 -preset ultrafast -tune stillimage \
    -pix_fmt yuv420p "$tmp/Der lange Film.mp4" || echo "FAIL ffmpeg film"
a shell "rm -rf $ROOT; mkdir -p '$DIR'"
for n in 1 2 3; do a push "$tmp/Dark.S01E0$n.mp4" "$DIR/" >/dev/null; done
a push "$tmp/Der lange Film.mp4" "$ROOT/" >/dev/null
for f in "$DIR/Dark.S01E01.mp4" "$DIR/Dark.S01E02.mp4" "$DIR/Dark.S01E03.mp4" "$ROOT/Der lange Film.mp4"; do
    a shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://${f// /%20}" >/dev/null 2>&1
done
sleep 3

wake; grant; a logcat -b crash -c
a shell am force-stop "$PKG"
a shell "run-as $PKG rm -f shared_prefs/video_watched.xml" 2>/dev/null
library() { a shell am start -W -n "$PKG/me.zhanghai.android.files.viewer.video.VideoLauncherActivity" >/dev/null; sleep 2.5; guard; }
# On a landscape phone one shelf fills the window (A15, qa8): scroll_to goes down, scroll_back up to TARGET.
scroll_back() {
    local i
    ui has "$1" && return 0
    for i in $(seq 1 "${2:-8}"); do scroll_in down || return 1; ui has "$1" && return 0; done
    return 1
}
# grep_to REGEX [SWIPES]: like scroll_to, for a part of a text or description.
grep_to() {
    local i
    ui texts | grep -qE -- "$1" && return 0
    for i in $(seq 1 "${2:-4}"); do scroll_in up || return 1; ui texts | grep -qE -- "$1" && return 0; done
    return 1
}
library
check "the library shows a Series shelf" ui wait "Serien|Series" 8
ui tap Dark >/dev/null
check "the series page lists its season" ui wait "Staffel 1|Season 1" 5
check "episodes in order with their number" eval "ui texts | grep -oE '(Folge|Episode) [123]' | awk '!seen[\$0]++' | head -3 | tr '\n' ' ' | grep -qE '1 .*2 .*3'"
back
# After the series page: the Movies shelf can lie below the window, and scrolling to it hid the Dark card.
check "and a Movies shelf with the 41-min video" grep_to 'Der lange Film' 4

ui tap actionSearch >/dev/null; sleep 1
a shell input text dark; sleep 1.5 # deliberate: the search waits for the end of typing (debounce) and then loads
# The hit's description, not only its title: on a landscape phone the titles start below the window (A15, qa8).
check "search finds the series' episodes" eventually 5 eval "ui texts | grep -q 'Dark\.S01E02'"
a shell input text zzzq; sleep 1.5 # deliberate: the search waits for the end of typing (debounce) and then loads
check "an empty search says so" ui wait "Keine Videos passen zu deiner Suche.|No videos match your search." 5
# The first back may only close the keyboard; the second only while the search field is still open.
back; ui has search_src_text && back

# Episodes watched in an earlier run (the R8 build cannot be reset through run-as) go back to unwatched first,
# so "Up next" can only be S1 E2.
for ep in Dark.S01E01 Dark.S01E02 Dark.S01E03; do
    scroll_to "$ep" 4 || continue
    ui longtap "$ep" >/dev/null
    ui tap "Weitere Optionen|More options" >/dev/null
    if ui has "Als ungesehen markieren|Mark as unwatched"; then
        ui tap "Als ungesehen markieren|Mark as unwatched" >/dev/null; sleep 2
    else
        back 1; ui has "1 ausgewählt|1 selected" && back 1 # the menu, then the selection
    fi
done
scroll_to Dark.S01E01 4 # on a phone "Recently added" starts at the newest episode, the grid lies below
ui longtap "Dark.S01E01" >/dev/null
check "a long press selects" ui wait "1 ausgewählt|1 selected" 4
ui tap "Weitere Optionen|More options" >/dev/null
ui tap "Als gesehen markieren|Mark as watched" >/dev/null
check "the selection ends" eval "! ui has 'ausgewählt|selected'"
# While the grid is still on screen; Continue watching lies above it, back at the top (scroll_back).
ui tap "Weitere Optionen|More options" >/dev/null
ui tap "Nur ungesehene|Unwatched only" >/dev/null
check "Unwatched only hides the watched episode from the grid" eval "! ui texts | grep -q 'Dark.S01E01'"
ui tap "Weitere Optionen|More options" >/dev/null
ui tap "Nur ungesehene|Unwatched only" >/dev/null; sleep 1.5
check "with S01E01 watched, Continue watching offers the next episode" scroll_back "Als Nächstes · S1 F2|Up next · S1 E2" 8

# "Automatisch weiter": the season is the queue, so S01E02 runs on into S01E03. The mode is one setting for all
# videos (the repeat button cycles it too, from wherever an earlier run left it): set through Ordo Video's
# settings ("Am Ende"), and back to "Anhalten" afterwards.
end_mode() {
    ui tap actionSettings >/dev/null
    ui tap "Am Ende|At the end" >/dev/null
    ui tap "$1" >/dev/null; sleep 1.5 # deliberate: the choice is taken over before the next step leaves the screen
    back 1.5
}
end_mode "Automatisch weiter|Play next automatically"
scroll_back "Als Nächstes · S1 F2|Up next · S1 E2" 8
ui tap "Als Nächstes · S1 F2|Up next · S1 E2" >/dev/null; wait_on viewer.video.VideoPlayerActivity 8
check "auto-advance stays in the season (S01E03 plays)" ui wait "Dark.S01E03" 22 # the rest of the episode, then the next
media_key PAUSE 1.5
back 2
library
end_mode "Anhalten|Stop at the end"

library
scroll_to "Der lange Film" 4 # its title, below the window on a phone (A15, qa8)
ui longtap "Der lange Film" >/dev/null
ui tap "In den Papierkorb|Move to trash|Löschen|Delete" >/dev/null; sleep 1 # deliberate: the question slides in
trash_asked() { local i; for i in 1 2 3; do ui texts | grep -qE "in den Papierkorb verschieben|to the trash|löschen\?|Delete 1" && return 0; sleep 1; done; return 1; }
check "trash asks first" trash_asked
ui tap "In den Papierkorb|Move to trash|Löschen|Delete" 2>/dev/null >/dev/null
check "the video went to Ordo's trash" eventually 6 eval "[ \"\$(trash_count ordo-e2e-serie)\" -ge 1 ]"
sleep 1 # deliberate: the row's leave animation, before a check that it is gone
check "and left the library" eval "! ui texts | grep -q 'Der lange Film'"

echo "SKIP Tresor playback (biometric prompt; check by hand as the header says)"
a shell am force-stop "$PKG"
finish
