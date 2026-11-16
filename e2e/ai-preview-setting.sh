#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Settings › AI assistant › "Try new commands (preview)": the switch is there, off on a fresh install, turns on and
# off, and keeps its state over an app restart; in German and in English. No model needed; leaves the switch off.
. "$(dirname "$0")/lib.sh" "$@"
PREVIEW="Neue Befehle ausprobieren (Vorschau)|Try new commands (preview)"
SETTINGS=me.zhanghai.android.files.settings.SettingsActivity
not_checked() { ! ui checked "$PREVIEW"; }
# inside a longer text (ui.py compares whole texts; the summary is several sentences)
in_text() { a shell uiautomator dump /sdcard/ordoai-ui.xml >/dev/null 2>&1; a shell cat /sdcard/ordoai-ui.xml | grep -qE "$1"; }
open_ai_settings() {
    a shell am start -W -n "$PKG/$SETTINGS" >/dev/null; sleep 2
    ui tap "KI-Assistent|AI assistant" >/dev/null; sleep 1
}
trap 'a shell cmd locale set-app-locales "$PKG" --locales "" >/dev/null 2>&1' EXIT
for locale in de-DE en-US; do
    a shell cmd locale set-app-locales "$PKG" --locales "$locale" >/dev/null 2>&1
    a shell am force-stop "$PKG"; launch
    open_ai_settings
    check "$locale: the preview switch is in the AI settings" ui has "$PREVIEW"
    check "$locale: it says what it does and that nothing changes without a confirmation" \
        in_text "Ohne deine Bestätigung ändert sich nichts.|Nothing changes without your confirmation."
    # A fresh install has it off; a run before may have left it on: then this is the "off" step of that run.
    ui checked "$PREVIEW" && { ui tap "$PREVIEW" >/dev/null; sleep 1; }
    check "$locale: off by default" not_checked
    ui tap "$PREVIEW" >/dev/null; sleep 1
    check "$locale: a tap turns it on" ui checked "$PREVIEW"
    a shell am force-stop "$PKG"; launch; open_ai_settings
    check "$locale: still on after a restart" ui checked "$PREVIEW"
    ui tap "$PREVIEW" >/dev/null; sleep 1
    a shell am force-stop "$PKG"; launch; open_ai_settings
    check "$locale: off again, also after a restart" not_checked
    back; back
done
finish
