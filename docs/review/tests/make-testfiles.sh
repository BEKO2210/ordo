#!/usr/bin/env bash
# Create a fixture tree for Ordo device tests.
# Usage: make-testfiles.sh [--out DIR] [--full]
#   Default: small fixtures only (no multi-gigabyte files).
#   --full:  also create sparse 4 GiB and 4 GiB+1 files, plus a ZIP64 archive if zip supports it.
set -euo pipefail

OUT=<tmp>
FULL=0
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    --full) FULL=1; shift ;;
    --help|-h)
      echo "Usage: $0 [--out DIR] [--full]"
      exit 0
      ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

have() { command -v "$1" >/dev/null 2>&1; }
skip() { echo "SKIP: $1"; }

mkdir -p "$OUT"
ROOT="$OUT/fixtures"
rm -rf "$ROOT"
mkdir -p "$ROOT"/{images,video,audio,docs,apk,archives,edge,names,hidden,links,payload}

echo "Writing fixtures under $ROOT"

# --- images ---
if have convert; then
  convert -size 32x32 xc:red "$ROOT/images/photo.jpg"
  convert -size 32x32 xc:green "$ROOT/images/photo.png"
  convert -size 32x32 xc:blue "$ROOT/images/photo.webp"
  convert -size 32x32 xc:yellow "$ROOT/images/icon.svg" 2>/dev/null \
    || printf '<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><rect width="32" height="32" fill="navy"/></svg>\n' > "$ROOT/images/icon.svg"
else
  skip "ImageMagick convert (jpg/png/webp generated as placeholders)"
  printf 'JPEG-PLACEHOLDER' > "$ROOT/images/photo.jpg"
  printf 'PNG-PLACEHOLDER' > "$ROOT/images/photo.png"
  printf 'WEBP-PLACEHOLDER' > "$ROOT/images/photo.webp"
  printf '<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><rect width="32" height="32" fill="navy"/></svg>\n' > "$ROOT/images/icon.svg"
fi
# HEIC / RAW / DNG: no portable encoder on a typical box
printf 'heic-placeholder' > "$ROOT/images/photo.heic"
printf 'raw-placeholder' > "$ROOT/images/photo.raw"
printf 'dng-placeholder' > "$ROOT/images/photo.dng"
# typische Kamerarohdaten-Endungen, ebenfalls als Platzhalter
for ext in arw cr2 nef orf rw2; do
  printf '%s-placeholder' "$ext" > "$ROOT/images/camera.$ext"
done

# --- video ---
if have ffmpeg; then
  ffmpeg -hide_banner -loglevel error -y -f lavfi -i color=c=red:s=64x64:d=0.2 -an "$ROOT/video/clip.mp4"
  ffmpeg -hide_banner -loglevel error -y -f lavfi -i color=c=green:s=64x64:d=0.2 -c:v libx264 -an "$ROOT/video/clip.mkv" 2>/dev/null \
    || ffmpeg -hide_banner -loglevel error -y -f lavfi -i color=c=green:s=64x64:d=0.2 -an "$ROOT/video/clip.mkv"
  ffmpeg -hide_banner -loglevel error -y -f lavfi -i color=c=blue:s=64x64:d=0.2 -c:v libvpx -an "$ROOT/video/clip.webm" 2>/dev/null \
    || printf 'webm-placeholder' > "$ROOT/video/clip.webm"
  ffmpeg -hide_banner -loglevel error -y -f lavfi -i color=c=yellow:s=64x64:d=0.2 -an "$ROOT/video/clip.mov" 2>/dev/null \
    || printf 'mov-placeholder' > "$ROOT/video/clip.mov"
else
  skip "ffmpeg (video placeholders only)"
  for ext in mp4 mkv webm mov; do printf '%s-placeholder' "$ext" > "$ROOT/video/clip.$ext"; done
fi

# --- audio ---
if have ffmpeg; then
  ffmpeg -hide_banner -loglevel error -y -f lavfi -i sine=f=440:d=0.2 "$ROOT/audio/tone.mp3"
  ffmpeg -hide_banner -loglevel error -y -f lavfi -i sine=f=440:d=0.2 "$ROOT/audio/tone.wav"
  ffmpeg -hide_banner -loglevel error -y -f lavfi -i sine=f=440:d=0.2 "$ROOT/audio/tone.flac" 2>/dev/null \
    || printf 'flac-placeholder' > "$ROOT/audio/tone.flac"
  ffmpeg -hide_banner -loglevel error -y -f lavfi -i sine=f=440:d=0.2 "$ROOT/audio/tone.m4a" 2>/dev/null \
    || printf 'm4a-placeholder' > "$ROOT/audio/tone.m4a"
  ffmpeg -hide_banner -loglevel error -y -f lavfi -i sine=f=440:d=0.2 -c:a libopus "$ROOT/audio/tone.opus" 2>/dev/null \
    || printf 'opus-placeholder' > "$ROOT/audio/tone.opus"
else
  skip "ffmpeg (audio placeholders only)"
  for ext in mp3 flac opus m4a wav; do printf '%s-placeholder' "$ext" > "$ROOT/audio/tone.$ext"; done
fi

# --- documents ---
printf 'Hello Ordo\n' > "$ROOT/docs/note.txt"
printf '# Title\n\nA markdown note.\n' > "$ROOT/docs/note.md"
# minimal PDF
cat > "$ROOT/docs/mini.pdf" << 'PDF'
%PDF-1.1
1 0 obj<< /Type /Catalog /Pages 2 0 R >>endobj
2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1 >>endobj
3 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>endobj
xref
0 4
0000000000 65535 f 
0000000009 00000 n 
0000000058 00000 n 
0000000115 00000 n 
trailer<< /Size 4 /Root 1 0 R >>
startxref
190
%%EOF
PDF
# Office / OpenDocument / EPUB are ZIP-based; write a minimal zip with a comment
pack_zip_named() {
  local dest="$1"; shift
  local tmp
  tmp=$(mktemp -d)
  printf 'placeholder for %s\n' "$(basename "$dest")" > "$tmp/content.txt"
  (cd "$tmp" && if have zip; then zip -q -X "$dest" content.txt; else python3 - << PY
import zipfile
zipfile.ZipFile("$dest","w").writestr("content.txt","placeholder\n")
PY
  fi)
  rm -rf "$tmp"
}
for f in document.docx sheet.xlsx slides.pptx text.odt book.epub; do
  pack_zip_named "$ROOT/docs/$f"
done

# --- apk placeholder (not a valid package; real APK is the review artifact) ---
printf 'apk-placeholder' > "$ROOT/apk/sample.apk"
if [ -f review/ordo-0.1.0-test4.apk ]; then
  ln -sfn review/ordo-0.1.0-test4.apk "$ROOT/apk/ordo-0.1.0-test4.apk"
fi

# --- archives: zip, 7z, rar, tar.gz ---
printf 'payload\n' > "$ROOT/payload/inside.txt"
if have zip; then
  (cd "$ROOT/payload" && zip -q "$ROOT/archives/ok.zip" inside.txt)
else
  python3 - << PY
import zipfile
zipfile.ZipFile("$ROOT/archives/ok.zip","w").write("$ROOT/payload/inside.txt","inside.txt")
PY
fi
if have 7z; then
  7z a -bd -y "$ROOT/archives/ok.7z" "$ROOT/payload/inside.txt" >/dev/null
else
  skip "7z (no ok.7z)"
fi
if have rar; then
  (cd "$ROOT/payload" && rar a -idq "$ROOT/archives/ok.rar" inside.txt)
elif have 7z; then
  7z a -bd -y "$ROOT/archives/ok.rar" "$ROOT/payload/inside.txt" >/dev/null || skip "rar via 7z"
else
  skip "rar (no ok.rar)"
fi
if have tar; then
  tar -C "$ROOT/payload" -czf "$ROOT/archives/ok.tar.gz" inside.txt
else
  skip "tar (no ok.tar.gz)"
fi

# zip-slip and symlink fixtures for extract tests
mkdir -p "$ROOT/archives/src"
printf 'innocent\n' > "$ROOT/archives/src/ok.txt"
python3 - << PY
import zipfile
path = "$ROOT/archives/zip-slip.zip"
with zipfile.ZipFile(path, "w") as z:
    z.write("$ROOT/archives/src/ok.txt", "ok.txt")
    z.writestr("../../slip-escaped.txt", "zip-slip-payload\n")
    z.writestr("<tmp> "absolute-path-payload\n")
print("wrote", path)
PY
# symlink loop + outbound symlink (for pack/copy/extract tests)
ln -sfn "loop-a" "$ROOT/links/loop-b"
ln -sfn "loop-b" "$ROOT/links/loop-a"
ln -sfn "/etc/passwd" "$ROOT/links/outside"
ln -sfn "../payload/inside.txt" "$ROOT/links/relative-ok"
printf 'regular\n' > "$ROOT/links/regular.txt"

# --- edge cases ---
: > "$ROOT/edge/zero.txt"
: > "$ROOT/edge/zero-no-ext"
printf 'no extension but text\n' > "$ROOT/edge/without_extension"
printf 'jpg bytes without extension\n' > "$ROOT/edge/looks-like-a-picture"
# hidden
printf 'hidden\n' > "$ROOT/hidden/.hidden.txt"
mkdir -p "$ROOT/hidden/.secret"
printf 'hidden dir\n' > "$ROOT/hidden/.secret/note.txt"
# long name (200 chars, stays under typical FS limits; a PATH_MAX case is in names/deep)
long=$(printf 'a%.0s' {1..200})
printf 'long-name\n' > "$ROOT/edge/${long}.txt"
# deep path (~20 levels, still creatable)
deep="$ROOT/names/deep"
for i in $(seq 1 20); do deep="$deep/d$i"; done
mkdir -p "$deep"
printf 'deep\n' > "$deep/file.txt"

# names: umlauts, emoji, NFC/NFD, spaces, quotes
printf 'umlaut\n' > "$ROOT/names/Grüße.txt"
printf 'emoji\n' > "$ROOT/names/😀 foto.txt"
printf 'space\n' > "$ROOT/names/file with spaces.txt"
python3 - << PY
import os, unicodedata
base = "$ROOT/names"
open(os.path.join(base, unicodedata.normalize("NFC", "Café.txt")), "w").write("nfc\n")
open(os.path.join(base, unicodedata.normalize("NFD", "Cafe\u0301-nfd.txt")), "w").write("nfd\n")
# a second NFC/NFD pair with the same visual name if the FS allows both
nfc = os.path.join(base, unicodedata.normalize("NFC", "Äöü"))
nfd = os.path.join(base, unicodedata.normalize("NFD", "Äöü"))
open(nfc, "w").write("nfc-folder-marker\n") if not os.path.exists(nfc) else None
if nfc != nfd and not os.path.exists(nfd):
    open(nfd, "w").write("nfd-folder-marker\n")
print("NFC/NFD written; same inode or name:" , nfc == nfd or (os.path.exists(nfc) and os.path.exists(nfd)))
PY

# --- 4 GiB sparse (only with --full) ---
if [ "$FULL" -eq 1 ]; then
  echo "Creating sparse 4 GiB files in $ROOT/edge"
  truncate -s 4294967296 "$ROOT/edge/sparse-4gib.bin"
  truncate -s 4294967297 "$ROOT/edge/sparse-4gib-plus-one.bin"
  if have zip; then
    # ZIP64: zip 3.0+ uses ZIP64 automatically for files >= 4 GiB
    (cd "$ROOT/edge" && zip -q "$ROOT/archives/zip64-4gib.zip" sparse-4gib.bin) || skip "zip64 archive"
  else
    skip "zip (no zip64 archive)"
  fi
else
  echo "Skipping 4 GiB sparse files (pass --full to create them)."
fi

# manifest
( cd "$ROOT" && find . -print | sort ) > "$OUT/MANIFEST.txt"
echo "Done. Manifest: $OUT/MANIFEST.txt"
echo "Root: $ROOT"
du -sh "$ROOT" || true
