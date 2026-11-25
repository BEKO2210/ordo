#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Copy-replace must not destroy the old target when the job is aborted or the process is killed
# (GB-7, GB-8). WRITES: creates /sdcard/ordo-e2e-copy-replace and removes it at the end.
. "$(dirname "$0")/lib.sh" "$@"
DIR=/sdcard/ordo-e2e-copy-replace
SRC=$DIR/ordoe2e-src
DST=$DIR/ordoe2e-dst
NAME=ordoe2e-big.bin
OLD=ORDO-OLD-TARGET
# Large enough that the copy is still running when we abort; FUSE on /sdcard is slower than a raw
# partition, so 48 MiB is usually a second or two.
SIZE_MB=48

a shell "rm -rf $DIR; mkdir -p $SRC $DST"
a shell "dd if=/dev/urandom of=$SRC/$NAME bs=1M count=$SIZE_MB 2>/dev/null"
a shell "printf '%s' $OLD > $DST/$NAME"
src_sum=$(a shell "md5sum $SRC/$NAME" | awk '{print $1}')
check "the old target still has its marker before the copy" \
    test "$(a shell "cat $DST/$NAME" | tr -d '\r')" = "$OLD"

launch
a shell am start -W -a android.intent.action.VIEW \
        -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" \
    -d "file://$DIR" >/dev/null
sleep 2
guard
check "the test folder opens" ui has ordoe2e-src

ui tap ordoe2e-src >/dev/null; sleep 2; guard
check "the source folder opens" ui has "$NAME"
ui longtap "$NAME" >/dev/null; sleep 1
ui tap "Kopieren|Copy" >/dev/null; sleep 1
back; sleep 1; guard
ui tap ordoe2e-dst >/dev/null; sleep 2; guard
check "the destination folder opens" ui has "$NAME"
# By its id: a phone shows the label in capitals ("HIERHER KOPIEREN", A15).
check "paste is offered" ui has action_paste
ui tap action_paste >/dev/null; sleep 1.5
# The word stands inside the title ("Datei „…“ überschreiben?"), so it is looked for in it; then its positive button.
check "the replace dialog appears" eval 'ui wait button1 5 >/dev/null && ui texts | grep -Eq "überschreiben|Replace"'
ui tap button1 >/dev/null
sleep 0.4
# GB-7: cancel the job. GB-8: if it is still running, kill the process so the half-written
# part file cannot become the destination.
a shell "cmd notification cancel_all" >/dev/null 2>&1
sleep 0.8
a shell am kill "$PKG" >/dev/null 2>&1
sleep 1

left=$(a shell "cat $DST/$NAME 2>/dev/null" | tr -d '\r')
new_sum=$(a shell "md5sum $DST/$NAME 2>/dev/null" | awk '{print $1}')
a shell "rm -f $DST/.$NAME.ordo-part"

if [ "$new_sum" = "$src_sum" ]; then
    echo "SKIP abort (the copy finished before kill; dest already matches the source)"
else
    check "aborting a replace keeps the old target" test "$left" = "$OLD"
fi

a shell "rm -rf $DIR"
finish
