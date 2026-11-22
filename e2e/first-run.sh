#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# farm: orient=once
# First run after a fresh install: the welcome pages appear, Skip closes them, the home screen then shows its
# categories and the storage card, and the next start goes straight home. Clears Ordo's data, so it runs only on
# an emulator; a real device prints SKIP and is left untouched.
. "$(dirname "$0")/lib.sh" "$@"
is_emulator || { echo "SKIP first-run: clears Ordo's data, runs only on an emulator"; exit 0; }
a shell pm clear "$PKG" >/dev/null
open_app
guard
check "a fresh start shows the welcome pages" on home.OnboardingActivity
check "the welcome pages can be skipped" ui has "Überspringen|Skip"
skip_onboarding
guard
check "skipping opens the home screen" on home.HomeActivity
check "home shows categories" ui wait "Audio" 10
check "home shows a storage card" ui wait analyzeButton 12
launch
check "the next start goes straight home" on home.HomeActivity
finish
