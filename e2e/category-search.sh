#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Regression (0.3.3): searching inside a category crashed while the search was loading. Read-only.
. "$(dirname "$0")/lib.sh" "$@"
launch
ui tap "Audio" >/dev/null; sleep 4; guard
check "category list opens" on filelist.FileListActivity
ui tap action_search >/dev/null; sleep 2
a shell input text zzordoe2e; sleep 5
check "still in the list after typing" on filelist.FileListActivity
check "search field holds the query" ui has "zzordoe2e"
back; back; back
finish
