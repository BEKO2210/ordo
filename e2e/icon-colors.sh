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
# The app's name is the title of its colour row. A test copy (applicationId .dev) is called "Ordo Dev" only where its
# build renamed app_name: the copies rename the English default, so in German its row is still titled "Ordo". The
# colour row's title is the last match on the page (the switch above has the same name).
case $PKG in *.dev) ORDO="Ordo Dev|Ordo" ;; *) ORDO="Ordo" ;; esac
MUSIC="Ordo Musik|Ordo Music"
COLOURS="Icon-Farben|Icon colours"
declare -A LABEL=(
    [Alias]="Icon-Farbe Blau|Icon colour Blue" [Teal]="Icon-Farbe Türkis|Icon colour Teal"
    [Green]="Icon-Farbe Grün|Icon colour Green" [Amber]="Icon-Farbe Bernstein|Icon colour Amber"
    [Red]="Icon-Farbe Rot|Icon colour Red" [Pink]="Icon-Farbe Pink|Icon colour Pink"
    [Violet]="Icon-Farbe Lila|Icon colour Violet" [Graphite]="Icon-Farbe Graphit|Icon colour Graphite"
)
# Ordo's own colour waits for Ordo to go to the background; its row says so in a line under the icon.
PENDING_HINT="Wechselt, sobald du Ordo verlässt|Changes as soon as you leave Ordo"
SAVED_MESSAGE="Gespeichert. „Ordo Musik“ bekommt die Icon-Farbe Lila, sobald du es einschaltest.|Saved. “Ordo Music” gets the icon colour Violet when you turn it on."

# The launcher entries of one icon (Home or Music) as the system lists them, e.g. "HomeGreen"; none while it is off.
# LABEL is keyed by the colour suffix ("Green", "Alias" = blue): strip the icon prefix before a lookup.
entries() {
    a shell "cmd package query-activities --components -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $PKG" |
        grep -o "launcher\.$1[A-Za-z]*" | sed 's/^launcher\.//' | sort
}
# wait_entries ICON WANT: until the system lists exactly WANT ("" = none) for that icon, up to 8 s.
wait_entries() { local end=$((SECONDS + 8)); until [ "$(entries "$1")" = "$2" ]; do [ $SECONDS -ge "$end" ] && return 1; sleep 0.5; done; }
alive() { a shell pidof "$PKG" >/dev/null && on settings.SettingsActivity; }

open_page() {
    a shell am start -W -n "$PKG/$SETTINGS" >/dev/null; guard
    ui tap "$SECTION" >/dev/null; ui wait "$MUSIC" 8 >/dev/null; guard
}
to_colours() { swipe_up; swipe_up; }
to_switches() { swipe_down; swipe_down; }
# tap_colour ROW SUFFIX: both rows have the same eight labels, so the swatch is the first one below the row's title
# and above the next title. A row partly off screen is scrolled into view first (a few short swipes).
ROW_TITLES="$ORDO|$MUSIC"
nudge() { read -r W H < <(screen_size); a shell input swipe $((W / 2)) $((H * $1 / 3)) $((W / 2)) $((H * $2 / 3)) 400; sleep 1.2; }
swatch() {
    local try title_y next_y x1 y1 x2 y2
    for try in 1 2 3 4 5 6 7 8; do
        title_y=$(ui bounds "$1" | tail -1 | awk '{print $4}')
        if [ -z "$title_y" ]; then nudge 1 2; continue; fi
        next_y=$(ui bounds "$ROW_TITLES" | awk -v t="$title_y" '$2 > t {print $2}' | sort -n | head -1)
        x1=
        read -r x1 y1 x2 y2 < <(ui bounds "${LABEL[$2]}" |
            awk -v t="$title_y" -v n="${next_y:-999999}" '$2 > t && $4 < n' | head -1)
        if [ -z "$x1" ]; then nudge 2 1; continue; fi
        echo $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
        return 0
    done
    return 1
}
tap_colour() { local xy; xy=$(swatch "$1" "$2") || return 1; a shell input tap $xy; sleep 1.2; }
# ordo_checked SUFFIX: with Ordo's row on screen its swatches come first in the screen read.
ordo_checked() { swatch "$ORDO" "$1" >/dev/null && ui checked "${LABEL[$1]}"; }
# Ordo's own icon changes once Ordo is in the background (One UI would otherwise close Ordo's task).
leave() { a shell input keyevent KEYCODE_HOME; sleep 3; }
tap_music_switch() { to_switches; ui tap "$MUSIC" title >/dev/null; to_colours; }

initial_home=$(entries Home)
initial_music=$(entries Music)
# The colour to switch Ordo to: never the one it already has (a tap on the chosen swatch changes nothing, so no hint).
NEW=Green; [ "$initial_home" = HomeGreen ] && NEW=Teal
restore() {
    trap - EXIT
    [ "$(entries Home)" = "$initial_home" ] && [ "$(entries Music)" = "$initial_music" ] && return 0
    open_page; to_colours
    home=${initial_home#Home}; tap_colour "$ORDO" "${home:-Alias}"; leave
    open_page; to_colours
    if [ -z "$initial_music" ]; then
        [ -n "$(entries Music)" ] && tap_music_switch
    else
        [ -z "$(entries Music)" ] && tap_music_switch
        tap_colour "$MUSIC" "${initial_music#Music}"
    fi
    sleep 2
    echo "restored: Home=$(entries Home) Music=$(entries Music)"
}
trap restore EXIT

launch
check "Ordo has exactly one launcher entry before the test" [ "$(entries Home | wc -l)" = 1 ]
open_page
# Looked for on the way down: two swipes carry the section title above a phone's window (A15, 25.11.2026). The
# swatches are found from any scroll position (swatch nudges the page).
check "the page has the icon colours" scroll_to "$COLOURS"
to_colours
check "the colour of Ordo shows the system's state" ordo_checked "${initial_home#Home}"

check "tapping ${NEW,,} in Ordo's row works" tap_colour "$ORDO" "$NEW"
check "the row says the icon changes once Ordo is left" ui wait "$PENDING_HINT" 5
check "${NEW,,} is selected" ordo_checked "$NEW"
check "Ordo's icon stays as it was while Ordo is open" wait_entries Home "$initial_home"
sleep 3
check "Ordo keeps running on its settings page" alive
leave
check "once Ordo is left, the system lists exactly the ${NEW,,} alias for Ordo" wait_entries Home "Home$NEW"

# Returning: the row reads the system state, not a stored value.
open_page; to_colours
check "after reopening ${NEW,,} is still selected" ordo_checked "$NEW"

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
check "Ordo still runs" alive
leave
check "the system lists exactly Ordo's blue alias again" wait_entries Home HomeAlias
restore
check "Ordo's launcher entry is as before the test" [ "$(entries Home)" = "$initial_home" ]
check "Ordo Musik's launcher entry is as before the test" [ "$(entries Music)" = "$initial_music" ]
a shell am force-stop "$PKG"
finish
