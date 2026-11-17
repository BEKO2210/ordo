#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Settings > Built-in apps: the "Ordo Musik" switch really enables and disables the launcher alias (checked with
# `cmd package query-activities`, not only on the screen), the other four apps are shown disabled with their summary,
# a calm explanation appears, the switch shows the real component state when the page is opened again, and the
# alias is put back to exactly the state it had before the test (also on Ctrl-C). Needs Android 11 or newer.
# On a real phone the shell may not change app components, so there the page's own switch sets and restores it.
. "$(dirname "$0")/lib.sh" "$@"
export E2E_FAST=0

SETTINGS=me.zhanghai.android.files.settings.SettingsActivity
ALIAS=me.zhanghai.android.files.launcher.MusicAlias
SECTION="Eingebaute Apps|Built-in apps"
MUSIC="Ordo Musik|Ordo Music"
SOON="Kommt, sobald die App fertig ist|Coming as soon as the app is ready"
ON_MESSAGE="„Ordo Musik“ ist eingeschaltet. Dein Startbildschirm braucht manchmal einen Moment, bis das Symbol erscheint.|“Ordo Music” is on. Your launcher can need a moment before the icon shows up."
OFF_MESSAGE="„Ordo Musik“ ist ausgeschaltet. Das Symbol verschwindet, sobald dein Startbildschirm sich aktualisiert hat.|“Ordo Music” is off. The icon disappears as soon as your launcher has refreshed."

# The launcher entries of Ordo as the system lists them: the alias is there only while it is enabled.
alias_on() { a shell "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $PKG" | grep -q "$ALIAS"; }
alias_off() { ! alias_on; }
not() { ! "$@"; }
wait_alias() { local want=$1 end=$((SECONDS + 8)); until "alias_$want"; do [ $SECONDS -ge "$end" ] && return 1; sleep 0.5; done; }
# A real device refuses `pm enable/disable` of an app component from the shell: there the page's own switch sets it.
set_alias() {
    if is_emulator; then a shell "pm $1 $PKG/$ALIAS" >/dev/null; return; fi
    local want=off; [ "$1" = enable ] && want=on
    "alias_$want" && return 0
    open_page; ui tap "$MUSIC" >/dev/null; wait_alias "$want"; back; sleep 1
}

if alias_on; then initial=enable; else initial=disable; fi
restore() { set_alias "$initial"; }
trap restore EXIT

open_page() {
    a shell am start -W -n "$PKG/$SETTINGS" >/dev/null; sleep 2; guard
    ui tap "$SECTION" >/dev/null; ui wait "$MUSIC" 8 >/dev/null; guard
}

launch
set_alias disable
check "the icon is off before the switch is used" wait_alias off
open_page
check "the page lists Ordo Musik" ui has "$MUSIC"
for app in "Video" "Bilder|Images" "PDF" "Notizen|Notes"; do
    check "$app is listed" ui has "$app"
done
check "a not yet ready app says when it comes" ui has "$SOON"
check "the Ordo Musik switch starts off" not ui checked "$MUSIC"

# The message first: it stays 6 s, and a screen read on a real phone takes over 2 s.
check "tapping the switch works" ui tap "$MUSIC"
check "a calm explanation is shown" ui wait "$ON_MESSAGE" 5
check "the launcher alias is enabled in the system" wait_alias on
check "the switch is on" ui checked "$MUSIC"

# Leaving and returning: the switch reads the system state, not a stored value.
back; sleep 1
open_page
check "after reopening the switch still shows the real state (on)" ui checked "$MUSIC"
if is_emulator; then
    a shell "pm disable $PKG/$ALIAS" >/dev/null
    back; sleep 1
    open_page
    check "a change made from outside shows up when the page opens (off)" not ui checked "$MUSIC"
    check "tapping the switch again works" ui tap "$MUSIC"
    check "the launcher alias is enabled again" wait_alias on
else
    echo "SKIP a change made from outside shows up when the page opens (only an emulator lets the shell change it)"
fi
check "tapping it off removes the icon" ui tap "$MUSIC"
check "the removal is explained" ui wait "$OFF_MESSAGE" 5
check "the launcher alias is disabled again" wait_alias off

# The icon's own entry: enabled once more, it opens Ordo's music start screen (a saved queue would open the player
# instead, so the start screen is asked for), which has "Choose folder".
set_alias enable
check "the alias can be started" wait_alias on
a shell am start -W -n "$PKG/$ALIAS" --ez me.zhanghai.android.files.viewer.audio.extra.SHOW_START true >/dev/null
sleep 3; guard
# Samsung names the alias as the front screen, the emulator its target: both are the music start screen.
music_screen() { on viewer.audio.MusicLauncherActivity || on launcher.MusicAlias; }
check "the music icon opens its own screen" music_screen
check "the start screen offers to choose a folder" ui has "Ordner wählen|Choose folder"
a shell am force-stop "$PKG"
finish
