# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Reads a logcat dump (stdin) and prints one finding per line for what debug builds report without crashing:
#   - a StrictMode policy violation whose stack contains Ordo code, and
#   - a LeakCanary heap analysis with application leaks (a count above 0).
# lib.sh's finish() runs it, so a violation or a leak turns the run red.
import re
import sys

OURS = ("me.zhanghai.android.files", "com.beko2210.ordo")
HEADER = re.compile(r"StrictMode: StrictMode policy violation")
DUMPING = re.compile(r"LeakCanary: .*Dumping the heap")
RESULT = re.compile(r"LeakCanary: .*\d+ APPLICATION LEAKS?")
LEAKS = re.compile(r"LeakCanary: .*?(\d+) APPLICATION LEAKS?")


def findings(text):
    found = []
    block = []

    def flush():
        if block and any(o in line for line in block for o in OURS):
            found.append("StrictMode: " + block[0].split("policy violation;", 1)[-1].strip())
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
    for finding in findings(sys.stdin.read()):
        print(finding)
