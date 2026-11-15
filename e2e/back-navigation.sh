#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Regression (0.3.2): back from a folder opened elsewhere returns to the caller, not up the folder path. Read-only.
. "$(dirname "$0")/lib.sh" "$@"
launch
swipe_up
if ui tap openButton >/dev/null 2>&1; then
    sleep 3; guard
    check "storage opens in the file list" on filelist.FileListActivity
    back 2
    check "one back returns to home" on home.HomeActivity
else
    fail "storage card not found"
fi
ui tap analyzeButton >/dev/null 2>&1; sleep 12; guard
check "analysis opens" on analysis.AnalysisActivity
back 2
check "back from the analysis returns to home" on home.HomeActivity
finish
