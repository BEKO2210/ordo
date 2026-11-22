#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Settings > Built-in apps: the "Ordo Musik" switch really enables and disables the launcher alias (checked with
# `cmd package query-activities`, not only on the screen), so does "Ordo Video" for its own alias, the other three
# apps are listed under "Coming soon",
# a calm explanation appears, the switch shows the real component state when the page is opened again, and the
# aliases are put back to exactly the state they had before the test (also on Ctrl-C). Needs Android 11 or newer.
# Where the shell may not change app components (real phones, newer Android), the page's own switch sets and restores it.
. "$(dirname "$0")/lib.sh" "$@"
export E2E_FAST=0

SETTINGS=me.zhanghai.android.files.settings.SettingsActivity
ALIAS=me.zhanghai.android.files.launcher.MusicAlias
VIDEO_ALIAS=me.zhanghai.android.files.launcher.VideoAlias
VIDEO="Ordo Video"
SECTION="Eingebaute Apps|Built-in apps"
MUSIC="Ordo Musik|Ordo Music"
SOON="Kommt, sobald die App fertig ist|Coming as soon as the app is ready"
ON_MESSAGE="„Ordo Musik“ ist eingeschaltet. Dein Startbildschirm braucht manchmal einen Moment, bis das Symbol erscheint.|“Ordo Music” is on. Your launcher can need a moment before the icon shows up."
OFF_MESSAGE="„Ordo Musik“ ist ausgeschaltet. Das Symbol verschwindet, sobald dein Startbildschirm sich aktualisiert hat.|“Ordo Music” is off. The icon disappears as soon as your launcher has refreshed."

# The launcher entries of Ordo as the system lists them: a music alias (one per icon colour) is there only while on.
alias_on() { a shell "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $PKG" | grep -q "launcher\.Music"; }
alias_off() { ! alias_on; }
video_on() { a shell "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $PKG" | grep -q "launcher\.Video"; }
video_off() { ! video_on; }
not() { ! "$@"; }
wait_alias() { local want=$1 end=$((SECONDS + 8)); until "alias_$want"; do [ $SECONDS -ge "$end" ] && return 1; sleep 0.5; done; }
# Newer Android (real phones, the API 36 emulator) refuses to let the shell switch an app component (a no-op is
# accepted, so it can't be asked beforehand): the page's own switch sets the alias, as a user would.
set_alias() {
    local want=off; [ "$1" = enable ] && want=on
    "alias_$want" && return 0
    open_page; ui tap "$MUSIC" title >/dev/null; wait_alias "$want"; back; sleep 1
}

if alias_on; then initial=enable; else initial=disable; fi
if video_on; then initial_video=on; else initial_video=off; fi
# The Ordo Video switch, set to a state through the page (the shell may not change components on newer Android).
set_video() {
    "video_$1" && return 0
    open_page; swipe_up; ui tap "$VIDEO" title >/dev/null
    local end=$((SECONDS + 8)); until "video_$1"; do [ $SECONDS -ge "$end" ] && break; sleep 0.5; done
    back; sleep 1; "video_$1"
}
restore() { set_alias "$initial"; set_video "$initial_video"; }
trap restore EXIT

open_page() {
    a shell am start -W -n "$PKG/$SETTINGS" >/dev/null; guard
    ui tap "$SECTION" >/dev/null; ui wait "$MUSIC" 8 >/dev/null; guard
}

launch
set_alias disable
check "the icon is off before the switch is used" wait_alias off
open_page
check "the page lists Ordo Musik" ui has "$MUSIC"
check "the Ordo Musik switch starts off" not ui checked "$MUSIC"
# The apps still to come sit at the end of the page, under "Coming soon".
swipe_up; swipe_up; swipe_up
for app in "Bilder|Images" "PDF" "Notizen|Notes"; do
    check "$app is listed" ui has "$app"
done
check "the apps still to come are grouped" ui has "Bald verfügbar|Coming soon"
check "a not yet ready app says when it comes" ui has "$SOON"
swipe_down; swipe_down; swipe_down

# The message first: it stays 6 s, and a screen read on a real phone takes over 2 s.
check "tapping the switch works" ui tap "$MUSIC" title
check "a calm explanation is shown" ui wait "$ON_MESSAGE" 5
check "the launcher alias is enabled in the system" wait_alias on
check "the switch is on" ui checked "$MUSIC"

# Leaving and returning: the switch reads the system state, not a stored value.
back; sleep 1
open_page
check "after reopening the switch still shows the real state (on)" ui checked "$MUSIC"
# Whether the shell may switch it shows only afterwards: refused, it either throws or quietly changes nothing.
a shell "pm disable $PKG/$ALIAS" >/dev/null 2>&1
if wait_alias off; then
    back; sleep 1
    open_page
    check "a change made from outside shows up when the page opens (off)" not ui checked "$MUSIC"
    check "tapping the switch again works" ui tap "$MUSIC" title
    check "the launcher alias is enabled again" wait_alias on
else
    echo "SKIP a change made from outside shows up when the page opens (the shell may not change app components here)"
fi
check "tapping it off removes the icon" ui tap "$MUSIC" title
check "the removal is explained" ui wait "$OFF_MESSAGE" 5
check "the launcher alias is disabled again" wait_alias off

# Ordo Video: its own switch and alias, off by default like the music icon; the alias opens the video library.
check "the page lists Ordo Video" eval 'ui has "$VIDEO" || { swipe_up; ui has "$VIDEO"; }'
set_video off
check "the Ordo Video icon is off before its switch is used" video_off
check "the Ordo Video switch turns its alias on" set_video on
a shell am start -W -n "$PKG/$VIDEO_ALIAS" >/dev/null; sleep 3; guard
video_screen() { on viewer.video.VideoLauncherActivity || on launcher.VideoAlias; }
check "the video icon opens its own screen" video_screen
back; sleep 1
check "the Ordo Video switch turns its alias off again" set_video off

# The icon's own entry: enabled once more, it opens Ordo's music library (also with a saved queue), with its tabs.
set_alias enable
check "the alias can be started" wait_alias on
a shell am start -W -n "$PKG/$ALIAS" >/dev/null
sleep 3; guard
# Samsung names the alias as the front screen, the emulator its target: both are the music start screen.
music_screen() { on viewer.audio.MusicLauncherActivity || on launcher.MusicAlias; }
check "the music icon opens its own screen" music_screen
check "the music library shows its tabs" ui has "Ordner|Folders"
a shell am force-stop "$PKG"
finish
