#!/bin/bash
# Picking a file for another app (an upload in the browser: the system picker's "Open from" list hands over to Ordo):
# Ordo opens in pick mode without asking for notifications, and tapping a file returns it and closes Ordo.
# Usage: e2e/pick-file.sh <serial>
. "$(dirname "$0")/lib.sh" "$1"
DIR=/storage/emulated/0/ordo-e2e-pick
wake; grant
a shell "rm -rf $DIR; mkdir -p $DIR"
trap 'a shell "rm -rf $DIR"' EXIT
python3 - "$DIR" <<'PY' | a shell "cat > $DIR/upload-test.wav"
import struct, sys, io, wave
buf = io.BytesIO()
with wave.open(buf, "wb") as out:
    out.setnchannels(2); out.setsampwidth(2); out.setframerate(44100)
    out.writeframes(struct.pack("<hh", 0, 0) * 4410)
sys.stdout.buffer.write(buf.getvalue())
PY
a shell pm revoke "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
a shell am force-stop "$PKG"
a shell am start -W -a android.intent.action.GET_CONTENT -c android.intent.category.OPENABLE -t "audio/*" \
    -n "$PKG/me.zhanghai.android.files.filelist.FileListActivity" >/dev/null
sleep 3
focus() { a shell dumpsys window | grep -m1 mCurrentFocus; }
no_prompt() { ! focus | grep -q GrantPermissions; }
ordo_closed() { ! focus | grep -q "$PKG/"; }
check "Ordo opens in pick mode" ui has "Wähle eine Datei aus|Select a file"
check "no notification prompt while picking" no_prompt
ui tap ordo-e2e-pick >/dev/null 2>&1; sleep 2
check "the test file is offered" ui has upload-test.wav
ui tap upload-test.wav >/dev/null; sleep 2
check "picking the file closes Ordo" ordo_closed
grant
finish
