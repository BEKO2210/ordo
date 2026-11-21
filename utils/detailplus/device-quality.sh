#!/bin/bash
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# SSIM-Y / PSNR-Y of the device screenshots (device-shots.sh) against the original frames, plus the colour spread.
cd ~/dpf-out
echo "tag,clip,crop,ssim_y,psnr_y,colour"
for f in shot_*_s_*.png; do
    tag=${f#shot_}; tag=${tag%%_s_*}; clip=${f##*_s_}; clip=${clip%.png}
    crop=$(python3 -c "
import numpy as np; from PIL import Image
a = np.asarray(Image.open('$f').convert('L'), 'f4')
r = np.where(a.mean(1) > 4)[0]; c = np.where(a.mean(0) > 4)[0]
print(f'crop={c[-1]-c[0]+1}:{r[-1]-r[0]+1}:{c[0]}:{r[0]}')")
    # Colour kept? Mean spread between the channels (0 = grey; a lost colour sampler showed as grey, 21.11.2026).
    col=$(python3 -c "
import numpy as np; from PIL import Image
a = np.asarray(Image.open('$f').convert('RGB'), 'f4'); print(round(float((a.max(2) - a.min(2)).mean()), 2))")
    IFS=: read W H X Y <<<"${crop#crop=}"
    g="[0:v]$crop,format=yuv444p[a];[1:v]scale=$W:$H:flags=lanczos,format=yuv444p[b];[a][b]"
    s=$(ffmpeg -hide_banner -i "$f" -i ../dpf-clips/ref_$clip.png -lavfi "${g}ssim" -f null - 2>&1 | grep -o 'Y:[0-9.]*' | tail -1)
    p=$(ffmpeg -hide_banner -i "$f" -i ../dpf-clips/ref_$clip.png -lavfi "${g}psnr" -f null - 2>&1 | grep -o 'y:[0-9.]*' | tail -1)
    echo "$tag,$clip,${W}x$H,${s#Y:},${p#y:},$col"
done
