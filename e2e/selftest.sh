#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Tests of the farm's own checks (a11y scanner, logcat check). No device needed.
cd "$(dirname "$0")" && exec python3 -m unittest test_scripts -v
