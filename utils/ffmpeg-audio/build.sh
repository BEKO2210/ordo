#!/bin/bash
# Copyright (c) 2026 Ordo contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds the native part of the :ffmpeg module (Media3's decoder_ffmpeg for sound, plus a software video decoder)
# from source, following libraries/decoder_ffmpeg/README.md of Media3 1.11.1: FFmpeg via the module's own
# build_ffmpeg.sh (with libswscale switched on for the video frames), then Media3's ffmpeg_jni.cc and Ordo's
# ffmpeg/src/main/jni/ffvideo.cc with ffmpeg/src/main/jni/CMakeLists.txt and the NDK's CMake toolchain. Writes
#   <out>/<abi>/libffmpegJNI.so   FFmpeg linked in statically, LGPL-2.1-or-later
# Media3's Java sources and proguard-rules.txt are vendored in ffmpeg/ (unchanged, Apache-2.0); after a Media3 update
# copy them from libraries/decoder_ffmpeg of the new tag by hand. Both sources are pinned to commit SHAs and checked
# after the fetch, because this runs their scripts (in CI before any signing secret exists, see play-internal.yml).
# Gradle runs it once per checkout (:ffmpeg:buildFfmpegAudio, about a minute); by hand:
# usage: utils/ffmpeg-audio/build.sh <out>   (Linux x86_64; needs git, make, cmake >= 3.21, the NDK below)
set -euo pipefail

MEDIA3_TAG=1.11.1                         # must match androidx_media3_version in app/build.gradle
MEDIA3_SHA=8c6678b657ede1e7883fc164ef73ed483c7796c3
FFMPEG_TAG=n6.0.1                         # release/6.0, the version the Media3 README recommends
FFMPEG_SHA=c41ff724ede7da657762d61097e26fac296c53bf
DECODERS=(ac3 eac3 dca truehd mlp mp1 mp2 # sound budget phones lack (W90: no AC3/E-AC3/DTS/TrueHD; Tab: no MPEG-1 Layer I/II, DVB)
    h264 hevc mpeg2video mpeg4 h263 msmpeg4v2 msmpeg4v3 vc1)  # video, only when the device's decoder can't
ABIS=(arm64-v8a armeabi-v7a x86_64)       # build_ffmpeg.sh also builds x86; Ordo does not ship it
MIN_SDK=24                                # app minSdk
# Not $ANDROID_NDK_HOME: GitHub runners point it at an older default NDK.
NDK=${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/30.0.16248370

here=$(cd "$(dirname "$0")" && pwd)
out=$(mkdir -p "${1:?usage: $0 <out-dir>}" && cd "$1" && pwd)
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
pinned() { [ "$(git -C "$1" rev-parse HEAD)" = "$2" ] || { echo "$1: not at pinned commit $2" >&2; exit 1; }; }

git clone -q --depth 1 --branch "$MEDIA3_TAG" --filter=blob:none --sparse https://github.com/androidx/media "$work/media"
pinned "$work/media" "$MEDIA3_SHA"
git -C "$work/media" sparse-checkout set libraries/decoder_ffmpeg
module=$work/media/libraries/decoder_ffmpeg/src/main
git clone -q --depth 1 --branch "$FFMPEG_TAG" https://git.ffmpeg.org/ffmpeg.git "$module/jni/ffmpeg"
pinned "$module/jni/ffmpeg" "$FFMPEG_SHA"

# No --enable-gpl, --enable-nonfree or --enable-version3 in build_ffmpeg.sh: FFmpeg stays LGPL-2.1-or-later.
# The only change to it: keep libswscale (10-bit and other pixel formats to RGBA for the screen).
grep -q -- '--disable-swscale' "$module/jni/build_ffmpeg.sh"
sed -i '/--disable-swscale/d' "$module/jni/build_ffmpeg.sh"
(cd "$module/jni" && ./build_ffmpeg.sh "$module" "$NDK" linux-x86_64 "$MIN_SDK" "${DECODERS[@]}")

bin=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
for abi in "${ABIS[@]}"; do
    # 16 KB pages (Google Play rule) set explicitly, not left to the NDK default. FFmpeg itself is only static
    # archives here; this link of libffmpegJNI.so is the one that sets the alignment.
    cmake -S "$here/../../ffmpeg/src/main/jni" -B "$work/jni-$abi" -DCMAKE_BUILD_TYPE=Release -DMEDIA3_JNI="$module/jni" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$MIN_SDK" -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
        -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" >/dev/null
    cmake --build "$work/jni-$abi" >/dev/null
    mkdir -p "$out/$abi"
    "$bin/llvm-strip" --strip-unneeded -o "$out/$abi/libffmpegJNI.so" "$work/jni-$abi/libffmpegJNI.so"
    for a in $("$bin/llvm-readelf" -lW "$out/$abi/libffmpegJNI.so" | awk '$1 == "LOAD" { print $NF }'); do
        (( a >= 16384 )) || { echo "$abi/libffmpegJNI.so: LOAD align $a < 16 KB" >&2; rm -f "$out/$abi/libffmpegJNI.so"; exit 1; }
    done
done
(cd "$out" && sha256sum */libffmpegJNI.so)
