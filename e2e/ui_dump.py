# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
"""Queries on a uiautomator XML dump that do not need adb."""

import re
import xml.etree.ElementTree as ET


BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def _matches(node, target):
    wanted = target.split("|")
    name = node.attrib.get("resource-id", "").split("/")[-1]
    return any(node.attrib.get(key, "") in wanted for key in ("text", "content-desc")) or name in wanted


def scrollable_bounds(xml, anchor=None):
    """Return the deepest usable scrollable container, optionally containing a visible anchor."""
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return None

    candidates = []

    def visit(node, depth):
        contains_anchor = _matches(node, anchor) if anchor else False
        for child in node:
            contains_anchor = visit(child, depth + 1) or contains_anchor
        match = BOUNDS.fullmatch(node.attrib.get("bounds", ""))
        if node.attrib.get("scrollable") == "true" and match and (not anchor or contains_anchor):
            bounds = tuple(map(int, match.groups()))
            width, height = bounds[2] - bounds[0], bounds[3] - bounds[1]
            # Excludes horizontal tab strips and zero-sized accessibility placeholders.
            if width >= 40 and height >= 80:
                candidates.append((depth, width * height, bounds))
        return contains_anchor

    visit(root, 0)
    if not candidates:
        return None
    # NestedScrollView/RecyclerView wins over an outer coordinator; area breaks ties between panes.
    return max(candidates, key=lambda item: (item[0], item[1]))[2]
