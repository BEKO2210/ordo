#!/usr/bin/env bash
# Regenerates tiny gapless fixtures for GaplessTrimTest. Idempotent.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

# LAME Xing tag carries encoder delay + padding (44100 Hz mono).
ffmpeg -y -f lavfi -i sine=f=1000:d=1 -ar 44100 -ac 1 -c:a libmp3lame -b:a 64k lame.mp3 </dev/null

# AAC-LC in MP4: ffmpeg writes an edit list (priming). Keep as-is for edit-list path.
ffmpeg -y -f lavfi -i sine=f=1000:d=1 -ar 44100 -ac 1 -c:a aac -b:a 64k edit_list.m4a </dev/null

# Same AAC file, then add iTunSMPB (Media3 GaplessInfoHolder parses this freeform tag).
cp -f edit_list.m4a itunsmpb.m4a
python3 - <<'PY'
from mutagen.mp4 import MP4, MP4FreeForm
path = "itunsmpb.m4a"
audio = MP4(path)
# delay 0x840=2112, padding 0x1C0=448, sample count 0xAC44=44100
value = b" 00000000 00000840 000001C0 000000000000AC44"
audio["----:com.apple.iTunes:iTunSMPB"] = [MP4FreeForm(value)]
audio.save()
PY

# Opus in Ogg: libopus only accepts 8/12/16/24/48 kHz; Identification header
# pre-skip is normally 312 at 48 kHz (Opus always decodes at 48 kHz).
ffmpeg -y -f lavfi -i sine=f=1000:d=1 -ar 48000 -ac 1 -c:a libopus -b:a 32k preskip.opus </dev/null

for f in lame.mp3 itunsmpb.m4a edit_list.m4a preskip.opus; do
  sz=$(wc -c < "$f")
  if [ "$sz" -ge 40960 ]; then
    echo "fixture too large: $f ($sz bytes)" >&2
    exit 1
  fi
  echo "ok $f ($sz bytes)"
done
