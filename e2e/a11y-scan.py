# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Reads one `uiautomator dump` (file argument) and prints a finding per line for every clickable element of Ordo that
#   - has neither text nor content-desc (and no labelled child: a row whose text sits in a child is fine), or
#   - is a touch target smaller than 48 dp in width or height.
# usage: a11y-scan.py DUMP.xml DENSITY_DPI
import sys
import xml.etree.ElementTree as ET

BOUNDS = "[%d,%d][%d,%d]"
MIN_DP = 48


def labelled(node):
    return any(n.get("text") or n.get("content-desc") for n in node.iter())


def findings(path, density):
    root = ET.parse(path).getroot()
    found = []
    for node in root.iter("node"):
        if node.get("clickable") != "true" or node.get("enabled") == "false":
            continue
        if not node.get("package", "").startswith("com.beko2210.ordo"):
            continue
        left, top, right, bottom = map(int, node.get("bounds").replace("][", ",").strip("[]").split(","))
        width, height = (right - left) * 160 / density, (bottom - top) * 160 / density
        if width <= 0 or height <= 0:
            continue
        name = node.get("resource-id") or node.get("class")
        where = BOUNDS % (left, top, right, bottom)
        if not labelled(node):
            found.append(f"unlabelled {name} {where}")
        if width < MIN_DP or height < MIN_DP:
            found.append(f"small {name} {width:.0f}x{height:.0f}dp {where}")
    return found


if __name__ == "__main__":
    for line in findings(sys.argv[1], int(sys.argv[2])):
        print(line)
