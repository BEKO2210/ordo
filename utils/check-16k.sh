#!/bin/bash
# Copyright (c) 2026 Ordo contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Checks Google Play's 16 KB page rule on a built APK or AAB:
# every lib/arm64-v8a/*.so has LOAD segments aligned to at least 2**14, and in an APK every
# uncompressed .so is zip-aligned to 16 KB (compressed ones, as with useLegacyPackaging, pass).
# usage: utils/check-16k.sh app/build/outputs/apk/beta/app-beta.apk   (needs the NDK and build-tools)
set -euo pipefail
f=$(realpath "${1:?usage: $0 <app.apk|app.aab>}")
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
readelf=$(ls "$sdk"/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf | tail -1)
zipalign=$(ls "$sdk"/build-tools/*/zipalign | sort -V | tail -1)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
unzip -q "$f" '*lib/arm64-v8a/*.so' -d "$tmp"
fail=0
while read -r so; do
    ok=OK; aligns=$("$readelf" -lW "$so" | awk '$1 == "LOAD" { print $NF }' | sort -u)
    for a in $aligns; do (( a >= 16384 )) || ok=FAIL; done
    [ $ok = OK ] || fail=1
    echo "$ok  LOAD align" $aligns " ${so#"$tmp"/}"
done < <(find "$tmp" -name '*.so' | sort)
if [[ $f == *.apk ]]; then
    "$zipalign" -c -P 16 -v 4 "$f" | grep 'lib/arm64-v8a/' || true
    "$zipalign" -c -P 16 4 "$f" && echo "OK  zip alignment (zipalign -c -P 16)" || { echo "FAIL  zip alignment"; fail=1; }
fi
[ $fail = 0 ] && echo "16 KB: OK" || { echo "16 KB: FAIL"; exit 1; }
