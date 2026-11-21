#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Clips for device-speed.sh and device-shots.sh, made in ~/dpf-clips from the 5-s clips of quality.sh (~/ordo-sr), looped
# to 3 min, no sound. The stretched sizes keep the display aspect, so the player sees 1267x540 and 1725x720 (and the
# 1080p clip stays below 1.5x on a 2960-px screen). Stills: one original frame each (ref_*.png) and its 960x540 clip.
set -e
mkdir -p ~/dpf-clips/Anime && cd ~/dpf-clips
L="-v error -y -stream_loop 36"; O="-t 180 -an -c:v libx264 -preset fast -crf 20 -pix_fmt yuv420p"
ffmpeg $L -i ~/ordo-sr/cgi_sintel1080.mp4 -vf scale=960:540:flags=bicubic $O c540.mp4
ffmpeg $L -i ~/ordo-sr/film_tos720.mp4 -vf scale=1280:720:flags=bicubic $O c720.mp4
ffmpeg $L -i ~/ordo-sr/cgi_sintel1080.mp4 -vf scale=1920:1080:flags=bicubic $O c1080.mp4
ffmpeg $L -i ~/ordo-sr/toon_sita1080.mp4 -vf scale=960:540:flags=bicubic $O "Anime/[SubsPlease] Sita - 01 (540p).mp4"
ffmpeg -v error -y -ss 2 -i ~/ordo-sr/toon_sita1080.mp4 -frames:v 1 -vf scale=1920:1080 ref_sita.png
ffmpeg -v error -y -ss 2 -i ~/ordo-sr/cgi_sintel1080.mp4 -frames:v 1 -vf crop=1440:810 ref_sintel.png
for n in sita sintel; do
    ffmpeg -v error -y -loop 1 -i ref_$n.png -t 40 -r 24 -vf "scale=960:540:flags=bicubic,format=yuv420p" -c:v libx264 \
        -crf 4 -preset fast s_$n.mp4
done
