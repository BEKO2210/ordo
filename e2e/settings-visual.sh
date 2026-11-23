#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# The settings overview and the visual controls in Appearance: every choice is present, selection survives reopening,
# and the inline accent palette updates. Every value changed here is restored before the test ends.
. "$(dirname "$0")/lib.sh" "$@"
export E2E_FAST=0

SETTINGS=me.zhanghai.android.files.settings.SettingsActivity
APPEARANCE="Aussehen|Appearance"
AI="KI-Assistent|AI assistant"
NIGHT_TITLE="Nachtmodus|Night mode"
NIGHT_SYSTEM="Nutze Systemeinstellung|Follow system"
NIGHT_OFF="Aus|Off"
NIGHT_ON="An|On"
NIGHT_TIME="Zeitbasiert|Based on time"
NIGHT_BATTERY="Basierend auf dem Batteriesparmodus|Based on battery saver"
MOTION_TITLE="Animationen|Motion"
MOTION_FULL="Voll – alle Effekte|Full – all effects"
MOTION_SUBTLE="Dezent – nur Übergänge|Subtle – transitions only"
MOTION_OFF="Aus|Off"
HEADER_TITLE="Suchleiste der Startseite|Home search bar"
HEADER_TOP="Oben|Top"
HEADER_BOTTOM="Unten – mit dem Daumen erreichbar|Bottom – within thumb reach"
ACCENT_TITLE="Akzentfarbe|Accent colour"
WALLPAPER="Farben vom Hintergrundbild|Wallpaper colours"
ACCENTS=("Aurora" "Blau|Blue" "Minze|Mint" "Violett|Violet" "Bernstein|Amber")

scroll_to() {
    local target=$1
    for _ in 1 2 3 4 5 6; do
        ui has "$target" && return 0
        swipe_up
    done
    return 1
}

open_overview() {
    a shell am start -W -n "$PKG/$SETTINGS" >/dev/null
    sleep 2
    guard
}

open_appearance() {
    ui wait "$AI" 8 >/dev/null
    ui tap "$APPEARANCE" >/dev/null
    ui wait "$NIGHT_TITLE" 8 >/dev/null
    guard
}

back_to_overview() {
    back
    ui wait "$AI" 8 >/dev/null
}

open_choice() {
    scroll_to "$1" || return 1
    ui tap "$1" >/dev/null
    ui wait choice_root 6 >/dev/null
}

selected_entry() {
    local entry
    for entry in "$@"; do
        # Dialog open: only a choice row counts (the summary behind repeats the selected words). Otherwise the
        # accent swatches, which are on the page.
        if ui has choice_root; then scroll_choice "$entry" || continue
        else ui has "$entry" || continue; fi
        if ui checked "$entry"; then
            echo "$entry"
            return 0
        fi
    done
    return 1
}

choose_and_wait() {
    if ! scroll_choice "$1"; then ui has choice_root && back; return 1; fi
    ui tap "$1" choice_root >/dev/null 2>&1 || ui tap "$1" choice_label >/dev/null || { ui has choice_root && back; return 1; }
    ui waitgone choice_root 8 >/dev/null
}

launch
open_overview

# The overview is ordered from top to bottom, so this also exercises scrolling to the last rows.
for row in "$APPEARANCE" "$AI" "Dateien & Ansicht|Files & view" "Speicher & Ordner|Storage & folders" \
    "Papierkorb|Trash" "Sicherung|Backup" "FTP-Server|FTP server" \
    "Impressum & Datenschutz|Legal notice & privacy" "Über|About"; do
    check "settings overview shows $row" scroll_to "$row"
done

back
open_overview
open_appearance

open_choice "$NIGHT_TITLE"
for option in "$NIGHT_SYSTEM" "$NIGHT_OFF" "$NIGHT_ON" "$NIGHT_TIME" "$NIGHT_BATTERY"; do
    check "night mode offers $option" scroll_choice "$option"
done
night_before=$(selected_entry "$NIGHT_SYSTEM" "$NIGHT_OFF" "$NIGHT_ON" "$NIGHT_TIME" "$NIGHT_BATTERY") || {
    fail "night mode has a selected option"
    night_before=$NIGHT_SYSTEM
}
choose_and_wait "$NIGHT_ON"
check "night mode summary changes to on" ui summary "$NIGHT_TITLE" "$NIGHT_ON"
open_choice "$NIGHT_TITLE"
check "night mode reopens with on selected" ui checked "$NIGHT_ON"
choose_and_wait "$night_before"
check "night mode is restored" ui summary "$NIGHT_TITLE" "$night_before"
back_to_overview

open_appearance
open_choice "$MOTION_TITLE"
for option in "$MOTION_FULL" "$MOTION_SUBTLE" "$MOTION_OFF"; do
    check "motion offers $option" scroll_choice "$option"
done
motion_before=$(selected_entry "$MOTION_FULL" "$MOTION_SUBTLE" "$MOTION_OFF") || {
    fail "motion has a selected option"
    motion_before=$MOTION_FULL
}
choose_and_wait "$MOTION_OFF"
check "motion summary changes to off" ui summary "$MOTION_TITLE" "$MOTION_OFF"
open_choice "$MOTION_TITLE"
check "motion reopens with off selected" ui checked "$MOTION_OFF"
choose_and_wait "$motion_before"
check "motion is restored" ui summary "$MOTION_TITLE" "$motion_before"
back_to_overview

open_appearance
open_choice "$HEADER_TITLE"
check "home header offers top" scroll_choice "$HEADER_TOP"
check "home header offers bottom" scroll_choice "$HEADER_BOTTOM"
header_before=$(selected_entry "$HEADER_TOP" "$HEADER_BOTTOM") || {
    fail "home header has a selected option"
    header_before=$HEADER_TOP
}
if [ "$header_before" = "$HEADER_TOP" ]; then header_other=$HEADER_BOTTOM; else header_other=$HEADER_TOP; fi
choose_and_wait "$header_other"
check "home header summary changes" ui summary "$HEADER_TITLE" "$header_other"
open_choice "$HEADER_TITLE"
check "home header reopens with the new position selected" ui checked "$header_other"
choose_and_wait "$header_before"
check "home header is restored" ui summary "$HEADER_TITLE" "$header_before"
back_to_overview

open_appearance
scroll_to "$ACCENT_TITLE"
wallpaper_was_on=0
if ui has "$WALLPAPER" && ui checked "$WALLPAPER"; then
    wallpaper_was_on=1
    ui tap "$WALLPAPER" >/dev/null
    ui wait swatches 8 >/dev/null
fi
check "accent colour shows its swatch row" ui has swatches
for accent in "${ACCENTS[@]}"; do
    check "accent colour offers $accent" ui has "$accent"
done
accent_before=$(selected_entry "${ACCENTS[@]}") || {
    fail "accent colour has a selected swatch"
    accent_before=${ACCENTS[0]}
}
# The accent is saved after its short check animation and the screen is then recreated: poll instead of reading once.
checked_soon() { local end=$((SECONDS + 4)); until ui checked "$1" >/dev/null 2>&1; do [ $SECONDS -ge "$end" ] && return 1; sleep 0.4; done; }
accent_other=${ACCENTS[0]}
[ "$accent_other" = "$accent_before" ] && accent_other=${ACCENTS[1]}
ui tap "$accent_other" >/dev/null
ui wait swatches 8 >/dev/null
check "accent row reflects the new colour" checked_soon "$accent_other"
check "accent row names the new colour" ui summary "$ACCENT_TITLE" "$accent_other"
ui tap "$accent_before" >/dev/null
ui wait swatches 8 >/dev/null
check "accent colour is restored" checked_soon "$accent_before"
check "accent row names the restored colour" ui summary "$ACCENT_TITLE" "$accent_before"
if [ "$wallpaper_was_on" = 1 ]; then
    ui tap "$WALLPAPER" >/dev/null
    ui wait "$ACCENT_TITLE" 8 >/dev/null
    check "wallpaper colours are restored" ui checked "$WALLPAPER"
fi

finish
