#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# FTP server factory defaults (blocker before 1.0.0). Needs a debug build (run-as) and PKG set to it, e.g.
# PKG=com.beko2210.ordo.dev: the FTP settings are taken out of Ordo's preferences for the run (so the factory defaults
# apply) and the preferences are put back exactly as they were at the end, also on Ctrl-C.
# On the FTP server screen (ftpserver.FtpServerActivity) out of the box: "Anonymous login" is off, the login has a
# password (the "Password" row shows a masked value, not "Not set"), there is no anonymous write access, and the root
# folder is not the whole internal storage. A named user with an empty password (e.g. admin, as on fix/stabil-p4) is as
# open as anonymous login and fails too. The preference keys are the same on every branch (key_ftp_server_anonymous_login,
# _username, _password); only their default values differ, so the suite reads the screen, not the resources.
# Live check, only on an emulator or with E2E_FTP_LIVE=1 (the server is reachable from the network while it runs, and
# out of the box that may mean the whole storage, writable, without a password): the server is started from its own
# switch, reached through `adb forward`, and Python's ftplib (control connection only, no data port needed) tries, each
# on a fresh connection, an anonymous login and an empty-password login for "admin" and for the username shown on the
# screen; any of them getting in fails. For every login that got in, MKD of a probe folder and CWD into Android/ (there
# only when the root is the whole storage) are tried too. The probe folder is removed again and the server stopped.
# Regression: Ordo shipped Material Files' defaults (anonymous login on, writing on, root = /storage/emulated/0); turning
# only anonymous off and leaving "admin" with an empty password is not enough.
. "$(dirname "$0")/lib.sh" "$@"
PREFS=shared_prefs/${PKG}_preferences.xml
PORT=2121 # pref_default_value_ftp_server_port
PROBE=ordo-e2e-ftp-probe
tmp=$(mktemp -d)
fwd=""
restore_prefs() {
    a shell am force-stop "$PKG" # also ends FtpServerService and with it the server
    [ -s "$tmp/prefs.xml" ] && a shell "run-as $PKG sh -c 'cat > $PREFS'" < "$tmp/prefs.xml"
}
if ! a shell run-as "$PKG" true 2>/dev/null; then
    echo "SKIP no debug build of $PKG (run-as refused): the factory defaults cannot be set and put back"
    exit 0
fi
trap '[ -n "$fwd" ] && "$ADB" -s "$SERIAL" forward --remove tcp:$fwd >/dev/null 2>&1; restore_prefs;
    a shell "rm -rf /storage/emulated/0/$PROBE"; rm -rf "$tmp"' EXIT

wake; grant; a logcat -b crash -c
a shell am force-stop "$PKG"
a shell "run-as $PKG cat $PREFS" > "$tmp/prefs.xml" 2>/dev/null
grep -q "<map" "$tmp/prefs.xml" || : > "$tmp/prefs.xml"
# Every key_ftp_server_* entry out: what is left is Ordo's own default for each of them.
python3 - "$tmp/prefs.xml" > "$tmp/prefs-defaults.xml" <<'PY'
import re, sys
xml = open(sys.argv[1]).read() or "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"
xml = re.sub(r'\s*<(\w+) name="key_ftp_server_[^"]*"[^>]*?/>', "", xml)
xml = re.sub(r'\s*<(\w+) name="key_ftp_server_[^"]*"[^>]*?>.*?</\1>', "", xml, flags=re.S)
print(xml, end="")
PY
a shell "run-as $PKG mkdir -p shared_prefs"
a shell "run-as $PKG sh -c 'cat > $PREFS'" < "$tmp/prefs-defaults.xml"
check "the FTP settings are back to the factory defaults" \
    bash -c "! '$ADB' -s '$SERIAL' shell run-as '$PKG' cat '$PREFS' | grep -q key_ftp_server_"

launch
a shell am start -W -a me.zhanghai.android.files.intent.action.MANAGE_FTP_SERVER \
    -n "$PKG/me.zhanghai.android.files.ftpserver.FtpServerActivity" >/dev/null
sleep 2; guard
check "the FTP server screen opens" on ftpserver.FtpServerActivity
check "the server is not running" ui summary Status "Nicht gestartet|Not started"
anon=0; write=0
ui checked "Anonymer Login|Anonymous login" && anon=1
# Summary of the row titled $1 (title|summary lines from `ui texts`).
row_summary() { ui texts | awk -F'|' -v t="^($1)\$" 'found && $2 == "summary" { print $1; exit } $2 == "title" { found = ($1 ~ t) }'; }
scroll_to "Benutzername|Username" 3 >/dev/null
user=$(row_summary "Benutzername|Username")
scroll_to "Passwort|Password" 3 >/dev/null
password=$(row_summary "Passwort|Password")
scroll_to "Erlaube Schreiben|Allow writing" 3 >/dev/null
ui checked "Erlaube Schreiben|Allow writing" && write=1
echo "INFO out of the box: anonymous login=$anon, allow writing=$write, username=${user:-?}, password row=${password:-?}"
check "out of the box: anonymous login is off" test "$anon" = 0
check "out of the box: no anonymous write access" test "$anon$write" != 11
# A set password is shown masked (bullets); an empty one shows "Not set" or nothing. Anything else counts as no password.
has_password() { grep -qE '^[•*●]+$' <<<"$password"; }
check "out of the box: the login has a password (no user with an empty password)" has_password
# The root folder row shows the folder it shares; the whole internal storage must not be the default.
scroll_to "Stammverzeichnis|Root folder" 3 >/dev/null
root=$(ui texts | awk -F'|' 'found && $2 == "summary" { print $1; exit } $2 == "title" { found = ($1 == "Stammverzeichnis" || $1 == "Root folder") }')
echo "INFO out of the box the root folder is: ${root:-?}"
whole() { grep -qxE '(/storage/emulated/0|/sdcard|file:///storage/emulated/0)/?|Interner Speicher|Internal storage' <<<"$1"; }
root_not_whole() { [ -n "$root" ] && ! whole "$root"; }
check "out of the box: the root folder is not the whole internal storage" root_not_whole

if is_emulator || [ "${E2E_FTP_LIVE:-}" = 1 ]; then
    swipe_down; swipe_down
    ui tap Status title >/dev/null; sleep 3; guard
    if ui summary Status "Läuft|Running"; then
        fwd=$("$ADB" -s "$SERIAL" forward tcp:0 tcp:$PORT | tr -d '\r')
        # One line: anon=0|1 nopass=<users let in with an empty password, or -> write=0|1 whole=0|1|?
        probe=$(python3 - "${fwd:-0}" "$PROBE" admin "$user" <<'PY'
import ftplib, sys
port, probe = int(sys.argv[1]), sys.argv[2]
users = []
for name in sys.argv[3:]:
    name = name.strip()
    if name and name != "?" and name.lower() != "anonymous" and name not in users:
        users.append(name)
anon = write = 0
whole = "?"
nopass = []
def attempt(user, password):
    """A logged-in connection or None. A fresh connection each time: failed logins may close it."""
    ftp = ftplib.FTP()
    ftp.connect("127.0.0.1", port, timeout=10)
    try:
        ftp.login(user, password)
        return ftp
    except ftplib.all_errors:
        try:
            ftp.close()
        except OSError:
            pass
        return None
try:
    opened = []
    ftp = attempt("anonymous", "ordo-e2e@example.invalid")
    if ftp:
        anon = 1
        opened.append(ftp)
    for user in users:
        ftp = attempt(user, "")
        if ftp:
            nopass.append(user)
            opened.append(ftp)
    for ftp in opened:
        try:
            ftp.mkd(probe); write = 1
            ftp.rmd(probe)
        except ftplib.all_errors:
            pass
        try:
            ftp.cwd("/Android"); whole = 1
        except ftplib.all_errors:
            if whole == "?":
                whole = 0
        try:
            ftp.close()
        except OSError:
            pass
except (OSError, EOFError) as e:
    print(f"error={e}")
    sys.exit(0)
print(f"anon={anon} nopass={','.join(nopass) or '-'} write={write} whole={whole}")
PY
)
        echo "INFO FTP probe on the device's port $PORT: ${probe:-no answer}"
        said() { grep -q "$1" <<<"$probe"; }
        not_said() { ! said "$1"; }
        check "the server answers over adb forward" said 'anon='
        check "live: an anonymous login is refused" said 'anon=0'
        check "live: an empty password is refused (admin and the configured user)" said 'nopass=- '
        check "live: no write without a password (MKD refused)" said 'write=0'
        check "live: the whole storage is not shared without a password (no Android/ at the root)" not_said 'whole=1'
        check "live: the probe folder is not left behind" a shell "test ! -e /storage/emulated/0/$PROBE"
        guard; ui tap Status title >/dev/null; sleep 3
        check "the server stops again" ui summary Status "Nicht gestartet|Not started"
    else
        fail "the server starts from its switch"
    fi
else
    echo "SKIP live FTP probe (only on an emulator or with E2E_FTP_LIVE=1: the server would be open on the network)"
fi
back 1
finish
