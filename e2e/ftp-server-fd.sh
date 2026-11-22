#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# The built-in FTP server must close the directory stream of every LIST (CU-2). WRITES: starts the
# FTP server, lists 2000 times, then stops it. Anonymous login is switched on for the run and off
# again at the end.
. "$(dirname "$0")/lib.sh" "$@"
PORT=2121
LISTS=2000
export SERIAL ADB

fds() {
    local pid
    pid=$(a shell pidof "$PKG" | tr -d '\r' | awk '{print $1}')
    [ -n "$pid" ] || return 1
    a shell "ls /proc/$pid/fd 2>/dev/null | wc -l" | tr -d '\r '
}

launch
a shell am start -W -n "$PKG/me.zhanghai.android.files.ftpserver.FtpServerActivity" >/dev/null
sleep 2
guard
check "the FTP server screen opens" on ftpserver.FtpServerActivity

# Configuration is locked while the server runs, so anonymous login is switched on first.
if ui has "Anonymer Login|Anonymous login"; then
    ui checked "Anonymer Login|Anonymous login" || {
        ui tap "Anonymer Login|Anonymous login" >/dev/null; sleep 0.5
    }
fi
ui tap "Status" >/dev/null; sleep 3
guard
check "the FTP server is running" ui has "Läuft|Running"

before=$(fds)
if [ -z "$before" ] || [ "$before" = 0 ]; then
    echo "SKIP fd count (/proc/pid/fd is not readable on this device)"
    ui tap "Status" >/dev/null; sleep 2
    finish
fi

a forward tcp:$PORT tcp:$PORT >/dev/null
python3 - "$PORT" "$LISTS" <<'PY'
import os, subprocess, sys
from ftplib import FTP

adb = os.environ.get("ADB", "adb")
serial = os.environ["SERIAL"]
port = int(sys.argv[1])
n = int(sys.argv[2])

class ForwardFtp(FTP):
    def __init__(self):
        super().__init__()
        self._fwd = None

    def makepasv(self):
        _host, data_port = super().makepasv()
        if self._fwd is not None:
            subprocess.call(
                [adb, "-s", serial, "forward", "--remove", "tcp:%d" % self._fwd],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
        subprocess.check_call(
            [adb, "-s", serial, "forward", "tcp:%d" % data_port, "tcp:%d" % data_port],
            stdout=subprocess.DEVNULL,
        )
        self._fwd = data_port
        return "127.0.0.1", data_port

ftp = ForwardFtp()
ftp.connect("127.0.0.1", port, timeout=10)
ftp.login()
for _ in range(n):
    ftp.retrlines("LIST", lambda _line: None)
ftp.quit()
PY
code=$?
a forward --remove tcp:$PORT >/dev/null 2>&1
check "2000 LIST commands finished" test "$code" = 0

after=$(fds)
echo "INFO fds before=$before after=$after"
# A leak of one fd per LIST would add 2000. Allow a little noise from the rest of the app.
check "the fd count stays within 80 of the start" test $((after - before)) -lt 80

ui tap "Status" >/dev/null; sleep 2
if ui has "Anonymer Login|Anonymous login" && ui checked "Anonymer Login|Anonymous login"; then
    ui tap "Anonymer Login|Anonymous login" >/dev/null; sleep 0.5
fi
finish
