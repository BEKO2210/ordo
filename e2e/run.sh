#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Runs Ordo's device tests against one device and prints a summary.
#   e2e/run.sh [--write] <adb serial>
# Without --write only tests that change nothing run. With --write the tests that create and delete their own
# files run too (never on a device whose owner has not agreed).
cd "$(dirname "$0")" || exit 1
write=0; [ "${1:-}" = "--write" ] && { write=1; shift; }
serial=${1:?usage: e2e/run.sh [--write] <adb serial>}
tests="first-run smoke settings-visual category-search back-navigation bars-replay home-header header-position home-pull drawer selection-more tablet-landscape analysis ai-show-guard documents-provider"
[ "$write" = 1 ] && tests="$tests stress-delete backup-restore home-auto-refresh audio-player cast klang klang-landscape klang-stress music-formats music-library launcher-icons icon-colors ai-show pick-file no-midword-breaks player-seek player-stage2 video-player video-picture"
failed=0
for t in $tests; do
    echo "== $t"
    ./$t.sh "$serial"; code=$?
    [ $code = 2 ] && { echo "run stopped: another app is in front"; exit 2; }
    [ $code = 0 ] || failed=$((failed + 1))
done
echo "== $(echo $tests | wc -w) tests, $failed failed"
[ "$failed" = 0 ]
