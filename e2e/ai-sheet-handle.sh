#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# AI command sheet drag handle: wrap_content with minWidth 48 dp, not a full-width focus band. Works with or
# without a downloaded model (setup page still has the handle).
. "$(dirname "$0")/lib.sh" "$@"
launch
ui tap assistantButton >/dev/null; sleep 2; guard
check "the command sheet shows its title" ui has titleText
check "the drag handle is on the sheet" ui has dragHandle

density=$(a shell wm density | tail -1 | sed 's/.*: //')
min_px=$((density * 48 / 160))
read -r hx1 _ hx2 _ < <(ui bounds dragHandle)
read -r cx1 _ cx2 _ < <(ui bounds contentLayout)
hw=$((${hx2:-0} - ${hx1:-0}))
cw=$((${cx2:-0} - ${cx1:-0}))
name="drag handle width is between 48 dp and a third of the sheet"
if [ "$cw" -le 0 ] || [ "$hw" -lt $((min_px - 1)) ] || [ "$hw" -gt $((cw / 3)) ]; then
    fail "$name (${hw}px, sheet ${cw}px, min ${min_px}px)"
else
    pass "$name (${hw}px of ${cw}px)"
fi
finish
