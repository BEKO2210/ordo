#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# The home screen's top bar (0.5.0), one row: side menu, search, assistant, more. Every control does its job and
# back returns to the home screen. Switches "show quick access" over and back again; otherwise read-only.
. "$(dirname "$0")/lib.sh" "$@"
launch
home() { on home.HomeActivity; }
sheet_closed() { ! ui has "headerOrb|setupLayout"; }
check "the menu icon has a label" ui has "Navigationsmenü öffnen|Open navigation drawer"
check "the wand has a label" ui has "Ordo einen Befehl geben|Give Ordo a command"
check "more has a label" ui has "Weitere Optionen|More options"

ui tap searchCard >/dev/null; sleep 3; guard
check "the search field opens the search in the file list" on filelist.FileListActivity
check "the search is ready for typing" ui has search_src_text
for _ in 1 2 3; do home && break; guard; back; done   # keyboard, search, list
check "back from the search returns to the home screen" home

ui tap assistantButton >/dev/null; sleep 2; guard
check_wand
back
ui has headerOrb && back   # the first back only hid the keyboard
check "back closes the command sheet" sheet_closed
check "the home screen is still there" home

# While the menu is open, only its entries are on screen for the driver.
more() { guard; ui tap moreButton >/dev/null 2>&1; sleep 1; }
more
check "more: recent files" ui has "Zuletzt verwendet|Recent files" title
check "more: hidden files" ui has "Versteckte Dateien anzeigen|Show hidden files" title
ui tap "Zuletzt verwendet|Recent files" title >/dev/null; sleep 1
check "recent files: grid" ui has "Raster|Grid" title
check "recent files: horizontal" ui has "Waagerecht|Horizontal" title
check "recent files: hide" ui has "Ausblenden|Hide" title
# Close the submenu and the menu. On a phone the popup does not cover the more button, so check the entries.
for _ in 1 2 3; do ui has "Raster|Grid|Versteckte Dateien anzeigen|Show hidden files" title || break; back; done
# The section may lie below the fold on a shorter screen, so read the tick of the menu entry instead.
quick() { more; ui checked "Schnellzugriff anzeigen|Show quick access" && echo shown || echo hidden; back 1; }
before=$(quick)
more; ui tap "Schnellzugriff anzeigen|Show quick access" title >/dev/null; sleep 1.5
check "show quick access switches the section" test "$(quick)" != "$before"
more; ui tap "Schnellzugriff anzeigen|Show quick access" title >/dev/null; sleep 1.5
check "and switches it back" test "$(quick)" = "$before"
more; ui tap "Einstellungen|Settings" title >/dev/null; sleep 2; guard
check "more: settings open" on settings.SettingsActivity
back 2
more; ui tap "Impressum & Datenschutz|Legal notice & privacy" title >/dev/null; sleep 2; guard
check "more: legal notice opens" on legal.LegalActivity
back 2
check "back returns to the home screen" home
finish
