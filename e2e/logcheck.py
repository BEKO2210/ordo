# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Reads a logcat dump (stdin) and prints one finding per line for what debug builds report without crashing:
#   - a StrictMode policy violation whose stack contains Ordo code, and
#   - a LeakCanary heap analysis with application leaks (a count above 0).
# lib.sh's finish() runs it, so a violation or a leak turns the run red. Exception (Belkis, 25.11.2026): a disk read or
# write whose ~duration is below SLOW_MS is printed as "INFO ..." and does not fail the run. StrictMode's ~duration is
# how long the main thread stayed busy after the violation, so a read early in a long onCreate still counts as slow.
import re
import sys

OURS = ("me.zhanghai.android.files", "com.beko2210.ordo")
HEADER = re.compile(r"StrictMode: StrictMode policy violation")
DUMPING = re.compile(r"LeakCanary: .*Dumping the heap")
RESULT = re.compile(r"LeakCanary: .*\d+ APPLICATION LEAKS?")
LEAKS = re.compile(r"LeakCanary: .*?(\d+) APPLICATION LEAKS?")
DISK = re.compile(r"~duration=(\d+) ms: android\.os\.strictmode\.Disk(Read|Write)Violation")
SLOW_MS = 100


def findings(text):
    """Every finding that fails the run."""
    return [f for f in report(text) if not f.startswith("INFO ")]


def report(text):
    """Findings that fail the run, plus "INFO ..." lines for short disk reads/writes. Each StrictMode line names the
    first Ordo frame, so the farm log says where the violation comes from."""
    found = []
    block = []

    def flush():
        if block and any(o in line for line in block for o in OURS):
            where = [line.split(" StrictMode: ", 1)[-1].strip() for line in block[1:] if any(o in line for o in OURS)]
            what = " ".join(["StrictMode: " + block[0].split("policy violation", 1)[-1].lstrip(";: ").strip()] + where[:1])
            disk = DISK.search(block[0])
            found.append("INFO " + what if disk and int(disk.group(1)) < SLOW_MS else what)
        block.clear()

    for line in text.splitlines():
        if HEADER.search(line):
            flush()
            block.append(line)
        elif block and " StrictMode: " in line:
            block.append(line)
        else:
            flush()
            leaks = LEAKS.search(line)
            if leaks and int(leaks.group(1)) > 0:
                found.append("LeakCanary: " + leaks.group(0).split("LeakCanary: ", 1)[-1].strip())
    flush()
    if pending(text):
        found.append("LeakCanary: heap analysis started but never finished")
    return found


def pending(text):
    """True when the last heap dump has no analysis result after it."""
    state = False
    for line in text.splitlines():
        if DUMPING.search(line):
            state = True
        elif RESULT.search(line):
            state = False
    return state


if __name__ == "__main__":
    if "--pending" in sys.argv:
        sys.exit(0 if pending(sys.stdin.read()) else 1)
    for finding in report(sys.stdin.read()):
        print(finding)
