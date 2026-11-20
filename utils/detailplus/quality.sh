#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Ordo "Detail plus" prototype (docs/VIDEO-PLAYER.md): original -> half size (bicubic) -> back up 2x with each method;
# PSNR/SSIM vs original. Runs in ~/ordo-sr on a machine with ffmpeg's libplacebo filter (Vulkan), with the two mpv
# shaders from app/src/main/assets/detailplus and 5-s clips: film_tos720.mp4 (Tears of Steel 720p, 5:30),
# cgi_sintel1080.mp4 (Sintel 1080p, 10:00), gfx_motion1080.mp4 (own motion graphics), toon_sita1080.mp4 (Sita Sings the
# Blues 1080p, archive.org, 25:00). The port itself is checked with dump.sh (MpvHook -> .frag) and harness.py.
set -e
cd ~/ordo-sr
L="-hide_banner -loglevel error -nostats"
A4K=$HOME/ordo-sr/Anime4K_Upscale_CNN_x2_M.glsl
FSR=$HOME/ordo-sr/FSRCNNX_x2_8-0-4-1.glsl
echo "clip,method,psnr_y,psnr_avg,ssim_y,ssim_all"
for c in film_tos720 cgi_sintel1080 gfx_motion1080 toon_sita1080; do
  ffmpeg $L -i $c.mp4 -vf "crop=trunc(iw/4)*4:trunc(ih/4)*4,format=yuv420p" -c:v ffv1 -y ref_$c.mkv
  IFS=x read W H < <(ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=s=x:p=0 ref_$c.mkv)
  ffmpeg $L -i ref_$c.mkv -vf "scale=$((W/2)):$((H/2)):flags=bicubic,format=yuv420p" -c:v ffv1 -y small_$c.mkv
  declare -A F=(
    [lanczos]="libplacebo=w=$W:h=$H:upscaler=lanczos:dithering=-1:format=yuv420p"
    [lanczos_cas04]="libplacebo=w=$W:h=$H:upscaler=lanczos:dithering=-1:format=yuv420p,cas=strength=0.4"
    [anime4k_m]="libplacebo=w=$W:h=$H:upscaler=lanczos:dithering=-1:format=yuv420p:custom_shader_path=$A4K"
    [fsrcnnx_8041]="libplacebo=w=$W:h=$H:upscaler=lanczos:dithering=-1:format=yuv420p:custom_shader_path=$FSR"
  )
  for m in lanczos lanczos_cas04 anime4k_m fsrcnnx_8041; do
    ffmpeg $L -init_hw_device vulkan -i small_$c.mkv -vf "${F[$m]}" -c:v ffv1 -y up_${c}_$m.mkv
    p=$(ffmpeg -hide_banner -nostats -i up_${c}_$m.mkv -i ref_$c.mkv -lavfi "[0:v][1:v]psnr" -f null - 2>&1 | grep -o 'y:[0-9.]* .*average:[0-9.inf]*' | tail -1)
    s=$(ffmpeg -hide_banner -nostats -i up_${c}_$m.mkv -i ref_$c.mkv -lavfi "[0:v][1:v]ssim" -f null - 2>&1 | grep -o 'Y:[0-9.]* .*All:[0-9.]*' | tail -1)
    py=$(sed -E 's/y:([0-9.]+).*/\1/' <<<"$p"); pa=$(sed -E 's/.*average:([0-9.inf]+).*/\1/' <<<"$p")
    sy=$(sed -E 's/Y:([0-9.]+).*/\1/' <<<"$s"); sa=$(sed -E 's/.*All:([0-9.]+).*/\1/' <<<"$s")
    echo "$c,$m,$py,$pa,$sy,$sa"
  done
done
