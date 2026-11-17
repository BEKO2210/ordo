#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Settings > Built-in apps > Icon colours: a swatch tapped in Ordo's row makes exactly that colour's alias Ordo's
# launcher entry (checked with `cmd package query-activities`, not only on the screen), Ordo keeps running, the
# choice shows again when the page is reopened; Ordo Musik's colour is remembered while its icon is off and used
# when it is switched on. Everything is put back as it was before the test (also on Ctrl-C). Needs Android 11 or
# newer. Only the page's own controls switch the aliases: newer Android refuses that to the shell.
. "$(dirname "$0")/lib.sh" "$@"
export E2E_FAST=0

SETTINGS=me.zhanghai.android.files.settings.SettingsActivity
SECTION="Eingebaute Apps|Built-in apps"
ORDO="Ordo"
MUSIC="Ordo Musik|Ordo Music"
COLOURS="Icon-Farben|Icon colours"
declare -A LABEL=(
    [Alias]="Icon-Farbe Blau|Icon colour Blue" [Teal]="Icon-Farbe Türkis|Icon colour Teal"
    [Green]="Icon-Farbe Grün|Icon colour Green" [Amber]="Icon-Farbe Bernstein|Icon colour Amber"
    [Red]="Icon-Farbe Rot|Icon colour Red" [Pink]="Icon-Farbe Pink|Icon colour Pink"
    [Violet]="Icon-Farbe Lila|Icon colour Violet" [Graphite]="Icon-Farbe Graphit|Icon colour Graphite"
)
GREEN_MESSAGE="„Ordo“ hat jetzt die Icon-Farbe Grün. Dein Startbildschirm braucht manchmal einen Moment, und auf manchen musst du das Symbol neu ablegen.|“Ordo” now has the icon colour Green. Your home screen can need a moment, and on some you have to place the icon again."
SAVED_MESSAGE="Gespeichert. „Ordo Musik“ bekommt die Icon-Farbe Lila, sobald du es einschaltest.|Saved. “Ordo Music” gets the icon colour Violet when you turn it on."

# The launcher entries of one icon (Home or Music) as the system lists them, e.g. "HomeGreen"; none while it is off.
entries() {
    a shell "cmd package query-activities --components -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $PKG" |
        grep -o "launcher\.$1[A-Za-z]*" | sed 's/^launcher\.//' | sort
}
# wait_entries ICON WANT: until the system lists exactly WANT ("" = none) for that icon, up to 8 s.
wait_entries() { local end=$((SECONDS + 8)); until [ "$(entries "$1")" = "$2" ]; do [ $SECONDS -ge "$end" ] && return 1; sleep 0.5; done; }
alive() { a shell pidof "$PKG" >/dev/null && on settings.SettingsActivity; }

open_page() {
    a shell am start -W -n "$PKG/$SETTINGS" >/dev/null; sleep 2; guard
    ui tap "$SECTION" >/dev/null; ui wait "$MUSIC" 8 >/dev/null; guard
}
to_colours() { swipe_up; swipe_up; }
to_switches() { swipe_down; swipe_down; }
# tap_colour ROW SUFFIX: both rows have the same eight labels, so the first swatch below the row's title is taken.
tap_colour() {
    local title_y x1 y1 x2 y2
    title_y=$(ui bounds "$1" | tail -1 | awk '{print $4}')
    [ -n "$title_y" ] || return 1
    read -r x1 y1 x2 y2 < <(ui bounds "${LABEL[$2]}" | awk -v t="$title_y" '$2 > t' | head -1)
    [ -n "${y2:-}" ] || return 1
    a shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2)); sleep 1.2
}
tap_music_switch() { to_switches; ui tap "$MUSIC" >/dev/null; to_colours; }

initial_home=$(entries Home)
initial_music=$(entries Music)
restore() {
    trap - EXIT
    [ "$(entries Home)" = "$initial_home" ] && [ "$(entries Music)" = "$initial_music" ] && return 0
    open_page; to_colours
    tap_colour "$ORDO" "${initial_home:-Alias}"
    if [ -z "$initial_music" ]; then
        [ -n "$(entries Music)" ] && tap_music_switch
    else
        [ -z "$(entries Music)" ] && tap_music_switch
        tap_colour "$MUSIC" "$initial_music"
    fi
    sleep 2
    echo "restored: Home=$(entries Home) Music=$(entries Music)"
}
trap restore EXIT

launch
check "Ordo has exactly one launcher entry before the test" [ "$(entries Home | wc -l)" = 1 ]
open_page
to_colours
check "the page has the icon colours" ui has "$COLOURS"
check "the colour of Ordo shows the system's state" ui checked "${LABEL[$initial_home]}"

check "tapping green in Ordo's row works" tap_colour "$ORDO" Green
check "the change is explained" ui wait "$GREEN_MESSAGE" 5
check "the system lists exactly the green alias for Ordo" wait_entries Home HomeGreen
check "green is selected" ui checked "${LABEL[Green]}"
sleep 3
check "Ordo keeps running on its settings page" alive

# Leaving and returning: the row reads the system state, not a stored value.
back; sleep 1
open_page; to_colours
check "after reopening green is still selected" ui checked "${LABEL[Green]}"

# Ordo Musik: a colour picked while its icon is off is remembered and used when it is switched on.
[ -n "$(entries Music)" ] && tap_music_switch
check "the music icon is off" wait_entries Music ""
check "tapping violet in Ordo Musik's row works" tap_colour "$MUSIC" Violet
check "the choice is saved for later" ui wait "$SAVED_MESSAGE" 5
check "no music icon appears from choosing a colour" wait_entries Music ""
tap_music_switch
check "switched on, the music icon comes in violet" wait_entries Music MusicViolet
check "tapping blue in Ordo Musik's row works" tap_colour "$MUSIC" Alias
check "the system lists exactly the blue music alias" wait_entries Music MusicAlias

check "tapping blue in Ordo's row works" tap_colour "$ORDO" Alias
check "the system lists exactly Ordo's blue alias again" wait_entries Home HomeAlias
check "Ordo still runs" alive
restore
check "Ordo's launcher entry is as before the test" [ "$(entries Home)" = "$initial_home" ]
check "Ordo Musik's launcher entry is as before the test" [ "$(entries Music)" = "$initial_music" ]
a shell am force-stop "$PKG"
finish
