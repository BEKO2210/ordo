#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Runs Ordo's device tests on every connected test device at once: each suite in portrait and in landscape, shared out
# over the devices, with a live summary and a final table. See README.md ("The farm").
#   e2e/farm.sh [--write] [--only "suite ..."] [--orient "portrait landscape"] [device ...]
# Devices are the names of farm-devices.conf (default: all of them); one that is offline, locked or fails
# preflight.sh is left out and the others take its share. Exit code: 0 all passed (a suite that failed once and
# passed on another device is listed as FLAKY), 1 a suite failed twice or ran out of time, 3 a suite could not run.
#
# FARM_LOCK  a command `<lock> <device> <cmd...>` that waits for the device, exports ORDO_SERIAL and runs the
#            command (pop-os: ~/ordo-geraet.sh, the default when it exists). Without one a device's name is its adb
#            serial and the lock is a flock in ~/.cache/ordo-e2e/.
# FARM_OUT   where logs, summary.txt and results.tsv go (default ~/.cache/ordo-e2e/farm/<start time>).
# FARM_NIGHT=1 also queues the release suites and e2e/monkey.sh, and is the only way the farm sets
#            E2E_SHADE, E2E_FTP_LIVE, E2E_VAULT_HAND and E2E_FILL_DISK. Without it those four are not queued.
# A device is locked per suite: between two suites other jobs get it. `touch <FARM_OUT>/hold/<device>` lends a device
# out for longer (its running suite finishes, no new one starts until the file is gone).
# Per suite, in a comment line of the suite itself:   # farm: needs=shots,hdr timeout=1200 orient=once
#   needs    capabilities a device must have in farm-devices.conf     timeout  seconds (default 900, x2 on "slow")
#   orient   "once": the suite does not depend on the orientation and runs in the first one only
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
CONF=$HERE/farm-devices.conf
CACHE=~/.cache/ordo-e2e
export ADB=${ADB:-$(command -v adb || echo "$HOME/Android/Sdk/platform-tools/adb")}
FARM_LOCK=${FARM_LOCK-$HOME/ordo-geraet.sh}
unset E2E_ALLOW_VOLUME # the farm never raises a volume

meta() { sed -n "s/^# farm:.* $2=\([^ ]*\).*/\1/p" "$HERE/$1.sh" | head -1; } # meta SUITE KEY
caps_of() { awk -v d="$1" '$1 == d { $1 = ""; print }' "$CONF"; }
can_run() { # can_run "CAPS" SUITE
    local need; for need in $(meta "$2" needs | tr ',' ' '); do case " $1 " in *" $need "*) ;; *) return 1 ;; esac; done
}
job_suite() { local j=${1##*/}; echo "${j#*__*__}"; }
job_orient() { local j=${1##*/}; j=${j#*__}; echo "${j%%__*}"; }

# ---------------------------------------------------------------- one job (runs inside the device's lock)
# Takes the next job this device may run, runs it and gives the device back as it was. The lock is held for this one
# suite only, so other jobs for the device get their turn between two suites.
# Exit code: 0 a job ran, 10 nothing to take right now, 11 the device is out (preflight failed, or a suite stopped
# because another app was in front).
if [ "${1:-}" = "--one" ]; then
    dev=$2; run=$3; s=${ORDO_SERIAL:?the lock command must export ORDO_SERIAL}
    caps=$(caps_of "$dev"); a() { "$ADB" -s "$s" "$@" < /dev/null; }
    say() { echo "$(date +%T) $dev: $*"; }
    # claim: the next job, moved to running/. First try: jobs in the orientation of this device's last job. A retry
    # goes to a device other than the one it failed on; the same one only when no other device here can run it.
    others_can() { local f; for f in "$run"/alive/*; do [ "${f##*/}" != "$dev" ] && [ -e "$f" ] && can_run "$(cat "$f")" "$1" && return 0; done; return 1; }
    claim() {
        local dir o f suite
        for dir in queue retry; do
            for o in "$prefer" portrait landscape; do
                for f in "$run/$dir"/*__"$o"__*; do
                    [ -e "$f" ] || continue; suite=$(job_suite "$f"); can_run "$caps" "$suite" || continue
                    if [ $dir = retry ] && [ "$(cat "$f" 2>/dev/null)" = "$dev" ] && others_can "$suite"; then continue; fi
                    failed_on=""; [ $dir = retry ] && failed_on=$(cat "$f" 2>/dev/null)
                    mv "$f" "$run/running/$dev" 2>/dev/null && { job=${f##*/}; return 0; }
                done
            done
        done
        return 1
    }
    give_back() { mv "$run/running/$dev" "$run/$([ -n "$failed_on" ] && echo retry || echo queue)/$job"; }
    prefer=$(cat "$run/prefer/$dev"); claim || exit 10
    # The whole check the first time, afterwards without bringing Ordo to the front (every suite does that itself).
    if ! E2E_PREFLIGHT_QUICK=$([ -e "$run/entered/$dev" ] && echo 1) "$HERE/preflight.sh" "$s" > "$run/logs/preflight-$dev.log" 2>&1; then
        give_back; say "left out, preflight failed:"; grep '^FAIL' "$run/logs/preflight-$dev.log"
        grep '^FAIL' "$run/logs/preflight-$dev.log" | head -1 > "$run/out/$dev"; exit 11
    fi
    : > "$run/entered/$dev"
    # What the job changes is put back, also when it is stopped: rotation, do-not-disturb, a size/density override.
    accel=$(a shell settings get system accelerometer_rotation | tr -d '\r'); rot=$(a shell settings get system user_rotation | tr -d '\r')
    zen=$(a shell settings get global zen_mode | tr -d '\r')
    hour=$((10#$(date +%H))); night=0; { [ $hour -ge 22 ] || [ $hour -lt 7 ]; } && night=1
    restore() {
        a shell "wm size | grep -q Override && wm size reset; wm density | grep -q Override && wm density reset"
        a shell "settings put system user_rotation ${rot:-0}; settings put system accelerometer_rotation ${accel:-1}"
        [ "$night" = 1 ] && [ "$zen" = 0 ] && a shell cmd notification set_dnd off
    }
    trap restore EXIT; trap 'pkill -TERM -P $$; [ -e "$run/running/$dev" ] && give_back; exit 130' INT TERM
    [ $night = 1 ] && a shell cmd notification set_dnd on # night: also no notification sound

    suite=$(job_suite "$job"); o=$(job_orient "$job"); echo "$o" > "$run/prefer/$dev"
    # A fixed rotation; which of the two is portrait depends on the device's natural orientation.
    for r in 0 1; do
        a shell "settings put system accelerometer_rotation 0; settings put system user_rotation $r"
        for i in 1 2 3 4 5 6 7 8 9 10; do
            read -r w h < <(a shell dumpsys window displays | grep -o 'cur=[0-9]*x[0-9]*' | head -1 | sed 's/cur=//; s/x/ /')
            if [ "$o" = portrait ]; then [ "${w:-0}" -lt "${h:-0}" ] && break 2; else [ "${w:-0}" -gt "${h:-0}" ] && break 2; fi
            sleep 0.3
        done
    done
    limit=$(meta "$suite" timeout); limit=${limit:-900}; case " $caps " in *" slow "*) limit=$((limit * 2)) ;; esac
    attempt=1; [ -n "$failed_on" ] && attempt=2
    log="$run/logs/$suite-$o-$dev-$attempt.log"; say "$suite ($o)$([ $attempt = 2 ] && echo ", retry after a failure on $failed_on")"
    began=$(date +%s)
    # The time limit starts here, inside the device lock: waiting for the device never eats it.
    # A place of its own per job for what a suite writes on the host (failure dumps, measured pictures).
    files="$run/files/$suite-$o-$dev"
    E2E_STATS="$run/sleep/$suite-$o-$dev-$attempt" E2E_FAILDUMP="$run/fail/$suite-$o-$dev" OUT="$files" E2E_SHOTS="$files" \
        timeout -k 30 "$limit" "$HERE/$suite.sh" "$s" > "$log" 2>&1 &
    wait $!; code=$? # in the background, so a signal is handled at once
    took=$(($(date +%s) - began))
    case $code in
        0) if grep -q '^PASS' "$log"; then result=PASS; else result=SKIP; fi ;;
        2) result=ABORT ;; 124 | 137) result=TIMEOUT ;; *) result=FAIL ;;
    esac
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$suite" "$o" "$dev" "$attempt" "$took" "$result" \
        "$(grep -c '^PASS' "$log")" "$(grep -c '^FAIL' "$log")" >> "$run/results.tsv"
    say "$suite ($o): $result in $took s"
    case $result in
        ABORT) # another app is in front or the volume is up: somebody uses the device. Hand the job back, stop here.
            give_back; grep -m1 '^ABORT' "$log" > "$run/out/$dev"; say "stops: $(cat "$run/out/$dev")"; exit 11 ;;
        FAIL | TIMEOUT) if [ $attempt = 1 ]; then echo "$dev" > "$run/running/$dev"; mv "$run/running/$dev" "$run/retry/$job"
            else rm -f "$run/running/$dev"; fi ;;
        *) rm -f "$run/running/$dev" ;;
    esac
    exit 0
fi

# ---------------------------------------------------------------- one device: job after job, each under its own lock
if [ "${1:-}" = "--worker" ]; then
    dev=$2; run=$3; index=$4
    lock_run() { # lock_run CMD...
        if [ -n "$FARM_LOCK" ] && [ -x "$FARM_LOCK" ]; then "$FARM_LOCK" "$dev" "$@"; return; fi
        ORDO_SERIAL=$dev flock "$CACHE/${dev//[^A-Za-z0-9]/_}.lock" "$@"
    }
    others_busy() { local f; for f in "$run"/running/*; do [ -e "$f" ] && [ "${f##*/}" != "$dev" ] && return 0; done; return 1; }
    caps_of "$dev" > "$run/alive/$dev"; trap 'rm -f "$run/alive/$dev"' EXIT
    [ $((index % 2)) = 1 ] && echo landscape > "$run/prefer/$dev" || echo portrait > "$run/prefer/$dev"
    while :; do
        # hold/<device> in the run's folder lends the device to somebody else: no new suite until the file is gone.
        while [ -e "$run/hold/$dev" ]; do sleep 5; done
        lock_run "$0" --one "$dev" "$run"
        case $? in
            0) ;;
            10) others_busy || break; sleep 2 ;; # another device's failure may still need this one
            *) break ;; # out, or the lock command could not reach the device
        esac
    done
    exit 0
fi

# ---------------------------------------------------------------- the farm
only=""; orients="portrait landscape"; devices=()
while [ $# -gt 0 ]; do
    case $1 in
        --write) ;; --only) only=$2; shift ;; --orient) orients=$2; shift ;;
        -h | --help) sed -n '2,20p' "$0"; exit 0 ;; *) devices+=("$1") ;;
    esac; shift
done
[ ${#devices[@]} -gt 0 ] || mapfile -t devices < <(awk '!/^#/ && NF { print $1 }' "$CONF")
# Every suite named in run.sh, including the ones that write their own files: the farm's devices are the test
# devices. --write is still accepted so the documented command keeps working; it does not change the list.
suites=$(sed -n 's/^tests="\([^"]*\)".*/\1/p; s/.*tests="\$tests \([^"]*\)".*/\1/p' "$HERE/run.sh")
# Shade, live FTP, the vault by hand and a full disk, plus monkey.sh and filejob-safety.sh: only with the night switch.
release="video-notification ftp-server-defaults vault-secure-flag text-editor-atomic-save"
if [ "${FARM_NIGHT:-}" = 1 ]; then
    export E2E_SHADE=1 E2E_FTP_LIVE=1 E2E_VAULT_HAND=1 E2E_FILL_DISK=1
    suites="$suites monkey filejob-safety"
else
    unset E2E_SHADE E2E_FTP_LIVE E2E_VAULT_HAND E2E_FILL_DISK
    kept=""
    for t in $suites; do case " $release " in *" $t "*) ;; *) kept="$kept $t" ;; esac; done
    suites=$kept
fi
[ -n "$only" ] && suites=$only
run=${FARM_OUT:-$CACHE/farm/$(date +%Y%m%d-%H%M%S)}
mkdir -p "$CACHE" "$run"/{sleep,prefer,hold,queue,retry,running,alive,entered,out,logs,fail}; : > "$run/results.tsv"

# Longest first (times of the last run), so the devices finish close together.
n=0; first=${orients%% *}; touch "$CACHE/farm-times.tsv"
for suite in $(for t in $suites; do echo "$(awk -v t="$t" '$1 == t { s = $2 } END { print s + 0 }' "$CACHE/farm-times.tsv") $t"; done | sort -s -rn -k1,1 | awk '{ print $2 }'); do
    [ -f "$HERE/$suite.sh" ] || { echo "no such suite: $suite"; exit 2; }
    for o in $orients; do
        [ "$(meta "$suite" orient)" = once ] && [ "$o" != "$first" ] && continue
        n=$((n + 1)); : > "$run/queue/$(printf '%03d' $n)__${o}__$suite"
    done
done
total=$n; started=$(date +%s)
echo "farm: $total jobs ($(echo $suites | wc -w) suites) on ${devices[*]}; live summary: $run/summary.txt"

declare -A pid; i=0
for d in "${devices[@]}"; do
    [ -n "$(caps_of "$d")" ] || echo "note: $d is not in farm-devices.conf, it only gets suites without needs"
    setsid "$0" --worker "$d" "$run" "$i" > "$run/logs/worker-$d.log" 2>&1 & # a process group of its own, to stop it whole
    pid[$d]=$!; i=$((i + 1))
done
trap 'for p in "${pid[@]}"; do kill -TERM -- "-$p" 2>/dev/null; done; wait; exit 130' INT TERM

verdicts() { # one line per job: suite orient result device seconds checks note
    for o in $orients; do for suite in $suites; do
        [ "$(meta "$suite" orient)" = once ] && [ "$o" != "$first" ] && continue
        awk -F'\t' -v s="$suite" -v o="$o" '$1 == s && $2 == o && $6 != "ABORT" { n++; r[n] = $6; d[n] = $3; t += $5; p = $7; f = $8 }
            END { if (!n) { print s, o, "NOT-RUN", "-", 0, "-", "-"; exit }
                  res = r[n]; note = "-"
                  if (n > 1 && r[n] == "PASS") { res = "FLAKY"; note = "failed-on-" d[1] }
                  if (n > 1 && r[n] != "PASS" && d[1] == d[n]) note = "twice-on-the-same-device"
                  print s, o, res, d[n], t, p "/" (p + f), note }' "$run/results.tsv"
    done; done
}
summary() {
    local v; v=$(verdicts)
    echo "Ordo device farm, $(date '+%F %T'), running for $(($(date +%s) - started)) s"
    echo "jobs: $total, $(awk '{ c[$3]++ } END { for (k in c) printf "%s %d  ", k, c[k] }' <<<"$v")"
    for d in "${devices[@]}"; do
        if [ -e "$run/running/$d" ]; then echo "  $d: running $(tail -1 "$run/logs/worker-$d.log" | cut -d' ' -f3-)"
        elif [ -s "$run/out/$d" ]; then echo "  $d: out, $(cat "$run/out/$d")"
        elif [ -e "$run/hold/$d" ]; then echo "  $d: on hold (hold/$d)"
        elif kill -0 "${pid[$d]}" 2>/dev/null; then echo "  $d: waiting for the device or for a job"
        else echo "  $d: done. $(tail -1 "$run/logs/worker-$d.log" 2>/dev/null | cut -d' ' -f2-)"; fi
    done
    echo; printf '%-22s %-9s %-7s %-5s %6s  %-8s %s\n' suite orient result device sec checks note
    while read -r s o r d t c note; do printf '%-22s %-9s %-7s %-5s %6s  %-8s %s\n' "$s" "$o" "$r" "$d" "$t" "$c" "${note/#-/}"; done <<<"$v"
}

# Wait on the workers themselves (their pids), never on a process search.
alive() { local d; for d in "$@"; do kill -0 "${pid[$d]}" 2>/dev/null && return 0; done; return 1; }
while alive "${devices[@]}"; do
    summary > "$run/summary.tmp" 2>/dev/null; mv "$run/summary.tmp" "$run/summary.txt"
    sleep 3
done
wait
summary > "$run/summary.txt"; cat "$run/summary.txt"
verdicts | awk '$5 > 0 { print $1, $5 }' | awk '{ if ($2 > m[$1]) m[$1] = $2 } END { for (k in m) print k, m[k] }' > "$CACHE/farm-times.tsv"
echo; echo "wall clock: $(($(date +%s) - started)) s, of it $(cat "$run"/sleep/* 2>/dev/null | awk '{ s += $1 } END { printf "%d", s }') s in sleeps of the test scripts; logs: $run/logs"
verdicts | awk '$3 == "FAIL" || $3 == "TIMEOUT" { bad = 1 } $3 == "NOT-RUN" { gap = 1 } END { exit bad ? 1 : (gap ? 3 : 0) }'
